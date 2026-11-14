//! Exact listener scopes. Device authentication, not Windows category, establishes trust.
use crate::{Error, Result};
use std::{
    collections::BTreeMap,
    net::{IpAddr, Ipv4Addr},
};
use uuid::Uuid;
use windows::Win32::{
    Networking::NetworkListManager::{
        INetworkListManager, NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED,
        NLM_NETWORK_CATEGORY_PRIVATE, NLM_NETWORK_CATEGORY_PUBLIC, NetworkListManager,
    },
    System::Com::{
        CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoUninitialize,
    },
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum NetworkCategory {
    Public,
    Private,
    Domain,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct NetworkProfile {
    pub id: Uuid,
    pub category: NetworkCategory,
}

/// Discovery metadata, not permission. The service always checks it against Windows again.
#[derive(Clone, Debug)]
pub struct NetworkInterface {
    pub adapter: Uuid,
    pub address: Ipv4Addr,
    pub name: String,
    pub profiles: Vec<NetworkProfile>,
    physical: bool,
}
impl NetworkInterface {
    pub fn new(
        adapter: Uuid,
        address: Ipv4Addr,
        name: String,
        profiles: Vec<NetworkProfile>,
    ) -> Self {
        Self {
            adapter,
            address,
            name,
            profiles,
            physical: false,
        }
    }
    pub fn with_physical(mut self, physical: bool) -> Self {
        self.physical = physical;
        self
    }
    pub fn is_physical(&self) -> bool {
        self.physical
    }

    pub fn key(&self) -> String {
        format!("{}/{}", self.adapter, self.address)
    }
    pub fn is_public(&self) -> bool {
        self.profiles
            .iter()
            .any(|p| p.category == NetworkCategory::Public)
    }
    /// A fresh OS-validated binding scope; no user-consent or trust decision.
    pub fn binding(&self) -> Result<NetworkBinding> {
        if self.profiles.is_empty()
            || self.address.is_unspecified()
            || self.address.is_loopback()
            || self.address.is_multicast()
            || self.address.is_link_local()
        {
            return Err(Error::Protocol("INTERFACE_NOT_PERMITTED"));
        }
        Ok(NetworkBinding {
            interface: self.clone(),
        })
    }
}

/// Ephemeral capability for exactly one adapter, address and Windows network/profile set.
/// Never persisted; every scope must still match fresh OS discovery.
#[derive(Clone, Debug)]
pub struct NetworkBinding {
    interface: NetworkInterface,
}
impl NetworkBinding {
    pub fn address(&self) -> Ipv4Addr {
        self.interface.address
    }
    pub fn permits(&self, current: &[NetworkInterface]) -> bool {
        current.iter().any(|i| {
            i.adapter == self.interface.adapter
                && i.address == self.interface.address
                && i.profiles == self.interface.profiles
        })
    }
    pub fn validate(&self) -> Result<()> {
        if self.permits(&available_interfaces()?) {
            Ok(())
        } else {
            Err(Error::Protocol("NETWORK_PROFILE_CHANGED"))
        }
    }
}

struct ComApartment(bool);
impl Drop for ComApartment {
    fn drop(&mut self) {
        if self.0 {
            unsafe { CoUninitialize() };
        }
    }
}

fn network_profiles() -> windows::core::Result<BTreeMap<Uuid, Vec<NetworkProfile>>> {
    let initialized = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
    if initialized.is_err() && initialized.0 != 0x80010106u32 as i32 {
        initialized.ok()?;
    }
    let _apartment = ComApartment(initialized.is_ok());
    unsafe {
        let manager: INetworkListManager = CoCreateInstance(&NetworkListManager, None, CLSCTX_ALL)?;
        let connections = manager.GetNetworkConnections()?;
        let mut profiles: BTreeMap<Uuid, Vec<NetworkProfile>> = BTreeMap::new();
        loop {
            let mut fetched = 0;
            let mut entry = [None];
            connections.Next(&mut entry, Some(&mut fetched))?;
            if fetched == 0 {
                break;
            }
            let connection = entry[0].take().ok_or_else(|| {
                windows::core::Error::from_hresult(windows::Win32::Foundation::E_FAIL)
            })?;
            if !connection.IsConnected()?.as_bool() {
                continue;
            }
            let network = connection.GetNetwork()?;
            let category = match network.GetCategory()? {
                NLM_NETWORK_CATEGORY_PRIVATE => NetworkCategory::Private,
                NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED => NetworkCategory::Domain,
                NLM_NETWORK_CATEGORY_PUBLIC => NetworkCategory::Public,
                _ => {
                    return Err(windows::core::Error::from_hresult(
                        windows::Win32::Foundation::E_FAIL,
                    ));
                }
            };
            profiles
                .entry(Uuid::from_u128(connection.GetAdapterId()?.to_u128()))
                .or_default()
                .push(NetworkProfile {
                    id: Uuid::from_u128(network.GetNetworkId()?.to_u128()),
                    category,
                });
        }
        for entries in profiles.values_mut() {
            entries.sort();
            entries.dedup();
        }
        Ok(profiles)
    }
}

pub fn available_interfaces() -> Result<Vec<NetworkInterface>> {
    let profiles =
        network_profiles().map_err(|_| Error::Protocol("NETWORK_PROFILE_UNAVAILABLE"))?;
    let mut interfaces = Vec::new();
    for interface in if_addrs::get_if_addrs()? {
        if interface.is_loopback() || !interface.is_oper_up() {
            continue;
        }
        let Ok(adapter) = Uuid::parse_str(&interface.adapter_name) else {
            continue;
        };
        let Some(profiles) = profiles.get(&adapter).filter(|p| !p.is_empty()) else {
            continue;
        };
        if let IpAddr::V4(address) = interface.ip()
            && !address.is_link_local()
            && !address.is_unspecified()
            && !address.is_multicast()
        {
            let name = interface
                .name
                .chars()
                .filter(|c| !c.is_control())
                .take(80)
                .collect();
            use windows::Win32::NetworkManagement::IpHelper::{GetIfEntry2, MIB_IF_ROW2};
            let mut row = MIB_IF_ROW2 {
                InterfaceIndex: interface.index.unwrap_or(0),
                ..Default::default()
            };
            // HardwareInterface is bit 0 in the Windows MIB_IF_ROW2 flags.
            let physical = row.InterfaceIndex != 0
                && unsafe { GetIfEntry2(&mut row) }.is_ok()
                && row.InterfaceAndOperStatusFlags._bitfield & 1 != 0;
            interfaces.push(
                NetworkInterface::new(adapter, address, name, profiles.clone())
                    .with_physical(physical),
            );
        }
    }
    interfaces.sort_by_key(|i| (!i.is_physical(), i.adapter, i.address));
    interfaces.dedup_by_key(|i| (i.adapter, i.address));
    Ok(interfaces)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn interface(category: NetworkCategory) -> NetworkInterface {
        NetworkInterface {
            adapter: Uuid::from_u128(1),
            address: Ipv4Addr::new(192, 168, 1, 2),
            name: "Wi-Fi".into(),
            physical: true,
            profiles: vec![NetworkProfile {
                id: Uuid::from_u128(2),
                category,
            }],
        }
    }
    #[test]
    fn all_windows_categories_bind_automatically_but_empty_profiles_are_denied() {
        let mut i = interface(NetworkCategory::Public);
        assert!(i.binding().is_ok());
        assert!(i.binding().unwrap().permits(std::slice::from_ref(&i)));
        i.profiles.clear();
        assert!(i.binding().is_err());
        assert!(interface(NetworkCategory::Private).binding().is_ok());
        assert!(interface(NetworkCategory::Domain).binding().is_ok());
    }
    #[test]
    fn binding_expires_on_any_identity_or_profile_change() {
        for category in [
            NetworkCategory::Public,
            NetworkCategory::Private,
            NetworkCategory::Domain,
        ] {
            let i = interface(category);
            let grant = i.binding().unwrap();
            assert!(!grant.permits(&[]));
            let mut changed = i.clone();
            changed.adapter = Uuid::from_u128(3);
            assert!(!grant.permits(&[changed]));
            let mut changed = i.clone();
            changed.address = Ipv4Addr::new(192, 168, 1, 3);
            assert!(!grant.permits(&[changed]));
            let mut changed = i.clone();
            changed.profiles[0].id = Uuid::from_u128(3);
            assert!(!grant.permits(&[changed]));
            let mut changed = i.clone();
            changed.profiles[0].category = if category == NetworkCategory::Public {
                NetworkCategory::Private
            } else {
                NetworkCategory::Public
            };
            assert!(!grant.permits(&[changed]));
            let mut changed = i.clone();
            changed.profiles.push(NetworkProfile {
                id: Uuid::from_u128(4),
                category,
            });
            assert!(!grant.permits(&[changed]));
            let mut renamed = i;
            renamed.name = "renamed".into();
            assert!(grant.permits(&[renamed]));
        }
    }
    #[test]
    fn mixed_adapter_profiles_do_not_require_an_extra_decision() {
        let mut i = interface(NetworkCategory::Domain);
        i.profiles.push(NetworkProfile {
            id: Uuid::from_u128(3),
            category: NetworkCategory::Public,
        });
        assert!(i.binding().is_ok());
    }
}
