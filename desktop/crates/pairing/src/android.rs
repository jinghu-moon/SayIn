use jni::{
    EnvUnowned,
    objects::{JObject, JString},
    sys::jint,
};
use zeroize::Zeroizing;

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
