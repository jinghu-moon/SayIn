//! Restrict private directory inheritance to the current Windows user and SYSTEM.
use crate::{Error, Result};
use std::{fs, path::Path};
use windows::{
    Win32::{
        Foundation::{CloseHandle, HANDLE, HLOCAL, LocalFree},
        Security::{
            Authorization::{
                ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
                SDDL_REVISION_1, SE_FILE_OBJECT, SetNamedSecurityInfoW,
            },
            DACL_SECURITY_INFORMATION, GetSecurityDescriptorDacl, GetTokenInformation,
            PROTECTED_DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, TOKEN_QUERY, TOKEN_USER,
            TokenUser,
        },
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    },
    core::{PCWSTR, PWSTR},
};

pub fn secure_directory(path: &Path) -> Result<()> {
    fs::create_dir_all(path)?;
    let mut token = HANDLE::default();
    // SAFETY: Query-only token and locally owned descriptor buffers; all native
    // allocations/handles are released on success and failure paths.
    unsafe {
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token)
            .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"))?;
        let result = (|| {
            let mut length = 0;
            let _ = GetTokenInformation(token, TokenUser, None, 0, &mut length);
            if length == 0 || length > 65536 {
                return Err(Error::Protocol("STORAGE_ACL_FAILED"));
            }
            let mut storage = vec![0u64; (length as usize).div_ceil(8)];
            GetTokenInformation(
                token,
                TokenUser,
                Some(storage.as_mut_ptr().cast()),
                length,
                &mut length,
            )
            .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"))?;
            let user = &*storage.as_ptr().cast::<TOKEN_USER>();
            let mut string = PWSTR::null();
            ConvertSidToStringSidW(user.User.Sid, &mut string)
                .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"))?;
            let sid = string
                .to_string()
                .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"));
            let _ = LocalFree(Some(HLOCAL(string.0.cast())));
            let sddl = format!("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{})", sid?);
            let wide = sddl.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
            let mut descriptor = PSECURITY_DESCRIPTOR::default();
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                PCWSTR(wide.as_ptr()),
                SDDL_REVISION_1,
                &mut descriptor,
                None,
            )
            .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"))?;
            let result = (|| {
                let (mut present, mut defaulted) = (Default::default(), Default::default());
                let mut acl = std::ptr::null_mut();
                GetSecurityDescriptorDacl(descriptor, &mut present, &mut acl, &mut defaulted)
                    .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"))?;
                if !present.as_bool() || acl.is_null() {
                    return Err(Error::Protocol("STORAGE_ACL_FAILED"));
                }
                use std::os::windows::ffi::OsStrExt;
                let wide = path
                    .as_os_str()
                    .encode_wide()
                    .chain(Some(0))
                    .collect::<Vec<_>>();
                SetNamedSecurityInfoW(
                    PCWSTR(wide.as_ptr()),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                    None,
                    None,
                    Some(acl),
                    None,
                )
                .ok()
                .map_err(|_| Error::Protocol("STORAGE_ACL_FAILED"))
            })();
            let _ = LocalFree(Some(HLOCAL(descriptor.0)));
            result
        })();
        let _ = CloseHandle(token);
        result
    }
}
