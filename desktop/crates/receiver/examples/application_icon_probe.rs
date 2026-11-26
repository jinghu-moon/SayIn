//! Query one explicitly supplied executable; no window activation or input.
use std::path::PathBuf;
use voicelink_receiver::{application_icon, application_name};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = std::env::args_os().skip(1);
    let executable = PathBuf::from(arguments.next().ok_or("executable path required")?);
    let output = PathBuf::from(arguments.next().ok_or("output WebP path required")?);
    if arguments.next().is_some() {
        return Err("expected executable and output WebP paths".into());
    }
    let name = application_name::display_name(&executable).ok_or("no application name")?;
    let bytes = application_icon::webp(&executable).ok_or("no bounded application icon")?;
    let image = image::load_from_memory(&bytes)?.into_rgba8();
    if image.dimensions() != (48, 48) || !image.pixels().any(|pixel| pixel.0[3] != 0) {
        return Err("invalid application icon".into());
    }
    std::fs::write(output, &bytes)?;
    println!(
        "{}",
        serde_json::json!({"name":name,"width":image.width(),"height":image.height(),"webp_bytes":bytes.len()})
    );
    Ok(())
}
