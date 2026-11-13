fn main() {
    for path in [
        "resources/sayin.rc",
        "assets/sayin.ico",
        "assets/sayin-tray-dark.ico",
        "assets/sayin-tray-light.ico",
    ] {
        println!("cargo:rerun-if-changed={path}");
    }
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        embed_resource::compile_for_everything("resources/sayin.rc", std::iter::empty::<&str>())
            .manifest_required()
            .expect("compile SayIn Windows icon and version resources");
    }
}
