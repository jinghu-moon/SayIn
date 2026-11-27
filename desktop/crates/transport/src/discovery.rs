//! Windows owns mDNS sockets; advertise only on the listener's validated interface.
//! Advertisements are untrusted routing hints, never pairing material.
use crate::{Error, Result, interfaces::NetworkBinding};
use std::{
    net::SocketAddr,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use windows::{
    Win32::NetworkManagement::Dns::{
        DNS_QUERY_REQUEST_VERSION1, DNS_SERVICE_CANCEL, DNS_SERVICE_INSTANCE,
        DNS_SERVICE_REGISTER_REQUEST, DnsServiceConstructInstance, DnsServiceDeRegister,
        DnsServiceFreeInstance, DnsServiceRegister, DnsServiceRegisterCancel,
    },
    core::{PCWSTR, PWSTR},
};

const DNS_REQUEST_PENDING: u32 = 9506;

pub(crate) fn computer_name() -> Option<String> {
    use windows::Win32::System::SystemInformation::{ComputerNameDnsHostname, GetComputerNameExW};
    let mut buffer = [0u16; 256];
    let mut length = buffer.len() as u32;
    unsafe {
        GetComputerNameExW(
            ComputerNameDnsHostname,
            Some(PWSTR(buffer.as_mut_ptr())),
            &mut length,
        )
    }
    .ok()?;
    String::from_utf16(buffer.get(..length as usize)?)
        .ok()
        .filter(|name| !name.is_empty() && name.len() <= 128 && !name.chars().any(char::is_control))
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PublicationStatus {
    Disabled,
    Publishing,
    Published,
    Unavailable,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    Registering,
    Registered,
    Unregistering,
    Finished,
}
struct State {
    phase: Phase,
    stopped: bool,
}

impl State {
    fn completed(&mut self, status: u32) -> bool {
        if self.phase == Phase::Registering && status == 0 {
            self.phase = if self.stopped {
                Phase::Unregistering
            } else {
                Phase::Registered
            };
            self.stopped
        } else {
            self.phase = Phase::Finished;
            false
        }
    }
    fn stop(&mut self) -> Option<Phase> {
        if self.stopped {
            return None;
        }
        self.stopped = true;
        let phase = self.phase;
        if phase == Phase::Registered {
            self.phase = Phase::Unregistering;
        }
        Some(phase)
    }
}

struct Registration {
    request: DNS_SERVICE_REGISTER_REQUEST,
    cancel: std::cell::UnsafeCell<DNS_SERVICE_CANCEL>,
    state: Mutex<State>,
}
// request and its constructed instance are immutable after creation. Windows owns
// cancel writes only during DnsServiceRegister, before the publisher is returned.
// The mutex serializes phase transitions; each outstanding callback owns an Arc.
unsafe impl Send for Registration {}
unsafe impl Sync for Registration {}
impl Drop for Registration {
    fn drop(&mut self) {
        unsafe { DnsServiceFreeInstance(self.request.pServiceInstance) }
    }
}
impl Registration {
    fn deregister(this: &Arc<Self>) {
        let callback_owner = Arc::into_raw(this.clone());
        let status = unsafe { DnsServiceDeRegister(&this.request, None) };
        if status != DNS_REQUEST_PENDING {
            // An immediate failure does not schedule a callback.
            unsafe { drop(Arc::from_raw(callback_owner)) };
            if let Ok(mut state) = this.state.lock() {
                state.phase = Phase::Finished;
            }
        }
    }
    fn stop(this: &Arc<Self>) {
        let phase = {
            let Ok(mut state) = this.state.lock() else {
                return;
            };
            let Some(phase) = state.stop() else {
                return;
            };
            phase
        };
        match phase {
            Phase::Registered => Self::deregister(this),
            Phase::Registering => unsafe {
                DnsServiceRegisterCancel(this.cancel.get());
            },
            _ => {}
        }
    }
}

unsafe extern "system" fn complete(
    status: u32,
    context: *const core::ffi::c_void,
    instance: *const DNS_SERVICE_INSTANCE,
) {
    // Windows gives a separate instance copy for each completion.
    if !instance.is_null() {
        unsafe { DnsServiceFreeInstance(instance.cast_mut()) };
    }
    let owner = unsafe { Arc::from_raw(context.cast::<Registration>()) };
    let withdraw = if let Ok(mut state) = owner.state.lock() {
        state.completed(status)
    } else {
        false
    };
    // Stop racing a successful registration must still withdraw it.
    if withdraw {
        Registration::deregister(&owner);
    }
}

pub(crate) struct Publisher {
    registration: Arc<Registration>,
    started: Instant,
}
impl Publisher {
    pub(crate) fn start(
        binding: &NetworkBinding,
        address: SocketAddr,
        host_id: &str,
    ) -> Result<Self> {
        if address.ip() != std::net::IpAddr::V4(binding.address()) || address.port() == 0 {
            return Err(Error::Protocol("INTERFACE_NOT_PERMITTED"));
        }
        let index = binding.interface_index()?; // Never zero (all interfaces).
        let wide = |s: &str| s.encode_utf16().chain(Some(0)).collect::<Vec<u16>>();
        let name = wide(&format!("SayIn-{host_id}._voicelink._tcp.local"));
        // Windows advertises PTR/SRV/TXT but does not create custom A records.
        // SRV discovery DNS is intentionally separate from the pinned TLS name.
        let computer = computer_name().ok_or(Error::Protocol("DISCOVERY_UNAVAILABLE"))?;
        if computer.is_empty()
            || computer.len() > 63
            || !computer
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-')
        {
            return Err(Error::Protocol("DISCOVERY_UNAVAILABLE"));
        }
        let hostname = wide(&format!("{computer}.local"));
        let keys = [wide("host_id"), wide("v"), wide("ipv4")];
        let values = [
            wide(host_id),
            wide("2"),
            wide(&binding.address().to_string()),
        ];
        let keys = keys.iter().map(|s| PCWSTR(s.as_ptr())).collect::<Vec<_>>();
        let values = values
            .iter()
            .map(|s| PCWSTR(s.as_ptr()))
            .collect::<Vec<_>>();
        let ip = u32::from_ne_bytes(binding.address().octets());
        let instance = unsafe {
            DnsServiceConstructInstance(
                PCWSTR(name.as_ptr()),
                PCWSTR(hostname.as_ptr()),
                Some(&ip),
                None,
                address.port(),
                0,
                0,
                3,
                keys.as_ptr(),
                values.as_ptr(),
            )
        };
        if instance.is_null() {
            return Err(Error::Protocol("DISCOVERY_UNAVAILABLE"));
        }
        // Both Windows scopes refer to the same selected interface.
        unsafe {
            (*instance).dwInterfaceIndex = index;
        }
        let registration = Arc::new_cyclic(|weak: &std::sync::Weak<Registration>| Registration {
            request: DNS_SERVICE_REGISTER_REQUEST {
                Version: DNS_QUERY_REQUEST_VERSION1.0,
                InterfaceIndex: index,
                pServiceInstance: instance,
                pRegisterCompletionCallback: Some(complete),
                pQueryContext: weak.as_ptr().cast_mut().cast(),
                ..Default::default()
            },
            cancel: std::cell::UnsafeCell::new(DNS_SERVICE_CANCEL::default()),
            state: Mutex::new(State {
                phase: Phase::Registering,
                stopped: false,
            }),
        });
        let callback_owner = Arc::into_raw(registration.clone());
        let status =
            unsafe { DnsServiceRegister(&registration.request, Some(registration.cancel.get())) };
        if status != DNS_REQUEST_PENDING {
            unsafe { drop(Arc::from_raw(callback_owner)) };
            return Err(Error::Protocol("DISCOVERY_UNAVAILABLE"));
        }
        Ok(Self {
            registration,
            started: Instant::now(),
        })
    }
    pub(crate) fn status(&self) -> PublicationStatus {
        self.registration
            .state
            .lock()
            .map(|state| {
                if state.stopped {
                    return PublicationStatus::Unavailable;
                }
                match state.phase {
                    Phase::Registering => PublicationStatus::Publishing,
                    Phase::Registered => PublicationStatus::Published,
                    _ => PublicationStatus::Unavailable,
                }
            })
            .unwrap_or(PublicationStatus::Unavailable)
    }
    pub(crate) fn maintain(&self) {
        let pending = self
            .registration
            .state
            .lock()
            .is_ok_and(|s| s.phase == Phase::Registering);
        if pending && self.started.elapsed() >= Duration::from_secs(5) {
            Registration::stop(&self.registration);
        }
    }
}
impl Drop for Publisher {
    fn drop(&mut self) {
        Registration::stop(&self.registration);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stop_racing_registration_withdraws_late_success_exactly_once() {
        let mut state = State {
            phase: Phase::Registering,
            stopped: false,
        };
        assert_eq!(state.stop(), Some(Phase::Registering));
        assert!(state.completed(0));
        assert_eq!(state.phase, Phase::Unregistering);
        assert_eq!(state.stop(), None);
        assert!(!state.completed(0));
        assert_eq!(state.phase, Phase::Finished);
    }
    #[test]
    fn registered_stop_and_registration_failure_do_not_republish() {
        let mut state = State {
            phase: Phase::Registering,
            stopped: false,
        };
        assert!(!state.completed(0));
        assert_eq!(state.stop(), Some(Phase::Registered));
        assert!(!state.completed(0));
        assert_eq!(state.stop(), None);
        let mut failed = State {
            phase: Phase::Registering,
            stopped: false,
        };
        assert!(!failed.completed(5));
        assert_eq!(failed.phase, Phase::Finished);
        assert_eq!(failed.stop(), Some(Phase::Finished));
    }
}
