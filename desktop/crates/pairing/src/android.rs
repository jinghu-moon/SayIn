use jni::{
    EnvUnowned,
    objects::{JObject, JString},
    sys::jint,
};
use zeroize::Zeroizing;

/// Public bionic API (<sys/system_properties.h>); no Java hidden APIs or shell.
#[link(name = "c")]
unsafe extern "C" {
    fn __system_property_find(name: *const std::ffi::c_char) -> *const std::ffi::c_void;
    fn __system_property_read_callback(
        property: *const std::ffi::c_void,
        callback: unsafe extern "C" fn(
            *mut std::ffi::c_void,
            *const std::ffi::c_char,
            *const std::ffi::c_char,
            u32,
        ),
        cookie: *mut std::ffi::c_void,
    );
}

unsafe extern "C" fn read_property(
    cookie: *mut std::ffi::c_void,
    _name: *const std::ffi::c_char,
    value: *const std::ffi::c_char,
    _serial: u32,
) {
    // SAFETY: bionic invokes this synchronously with a valid NUL-terminated
    // value; cookie points to our live, exclusively borrowed local String.
    unsafe {
        *cookie.cast::<String>() = std::ffi::CStr::from_ptr(value)
            .to_string_lossy()
            .into_owned();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_seeyuer_sayin_pairing_NativePairing_readDeviceMarketName<'caller>(
    mut env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    key: JString<'caller>,
) -> JString<'caller> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let key = key.to_string();
        let key = match key.as_str() {
            "ro.product.marketname" => c"ro.product.marketname",
            "ro.product.vendor.marketname" => c"ro.product.vendor.marketname",
            "ro.product.odm.marketname" => c"ro.product.odm.marketname",
            _ => return JString::from_str(env, ""),
        };
        let mut value = String::new();
        // SAFETY: whitelisted static key. API 26+ read_callback supports long
        // read-only values without the deprecated getter's fixed-size buffer.
        unsafe {
            let property = __system_property_find(key.as_ptr());
            if !property.is_null() {
                __system_property_read_callback(
                    property,
                    read_property,
                    (&mut value as *mut String).cast(),
                );
            }
        }
        JString::from_str(env, value)
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// No native handles or cross-thread JNI references: one bounded bootstrap per
/// call, invoked from Kotlin's IO dispatcher. with_env contains Rust panics.
#[unsafe(no_mangle)]
pub extern "system" fn Java_seeyuer_sayin_pairing_NativePairing_pairCode<'caller>(
    mut env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    address: JString<'caller>,
    port: jint,
    code: JString<'caller>,
) -> JString<'caller> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let address = address.to_string();
        let code = Zeroizing::new(code.to_string());
        let result = (|| {
            let address = address.parse().map_err(|_| super::Error::Invalid)?;
            let port = u16::try_from(port).map_err(|_| super::Error::Invalid)?;
            super::pair_code(address, port, &code)
        })();
        let response = match result {
            Ok(invitation) => serde_json::json!({"ok":true,"invitation":invitation}),
            Err(error) => serde_json::json!({"ok":false,"code":error.to_string()}),
        };
        let text = Zeroizing::new(response.to_string());
        JString::from_str(env, &*text)
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
