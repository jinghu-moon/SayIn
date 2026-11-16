//! Local executable resources only. Call on a background worker, never in render.
use std::path::Path;

pub const ICON_EDGE: u32 = 48;
pub const MAX_ICON_BYTES: usize = 12 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ApplicationDisplay {
    pub name: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        with = "encoded_icon"
    )]
    pub icon_png: Option<Vec<u8>>,
}

impl ApplicationDisplay {
    pub fn capture(path: &Path) -> Option<Self> {
        let name = crate::application_name::display_name(path).or_else(|| {
            path.file_stem()?
                .to_str()
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
        })?;
        let display = Self {
            name,
            icon_png: png(path),
        };
        display.valid().then_some(display)
    }

    pub fn valid(&self) -> bool {
        !self.name.trim().is_empty()
            && self.name.chars().count() <= 96
            && !self.name.chars().any(char::is_control)
            && self.icon_png.as_ref().is_none_or(|bytes| {
                bytes.len() <= MAX_ICON_BYTES
                    && image::ImageReader::new(std::io::Cursor::new(bytes))
                        .with_guessed_format()
                        .ok()
                        .filter(|r| r.format() == Some(image::ImageFormat::Png))
                        .and_then(|r| r.into_dimensions().ok())
                        == Some((ICON_EDGE, ICON_EDGE))
            })
    }
}

mod encoded_icon {
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(
        value: &Option<Vec<u8>>,
        serializer: S,
    ) -> Result<S::Ok, S::Error> {
        serializer.serialize_some(&STANDARD.encode(value.as_ref().expect("skip none")))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(
        deserializer: D,
    ) -> Result<Option<Vec<u8>>, D::Error> {
        let value = Option::<String>::deserialize(deserializer)?;
        value
            .map(|s| {
                if s.len() > 16384 {
                    return Err(serde::de::Error::custom("ICON_LIMIT"));
                }
                let bytes = STANDARD.decode(&s).map_err(serde::de::Error::custom)?;
                if STANDARD.encode(&bytes) != s {
                    return Err(serde::de::Error::custom("ICON_ENCODING"));
                }
                Ok(bytes)
            })
            .transpose()
    }
}

pub fn png(path: &Path) -> Option<Vec<u8>> {
    if !path.is_absolute() || path.to_string_lossy().starts_with(r"\\") {
        return None;
    }
    extract(path)
}

#[cfg(windows)]
fn extract(path: &Path) -> Option<Vec<u8>> {
    let icon = win_icon_extractor::extract_icon_best(path, ICON_EDGE).ok()?;
    let png = win_icon_extractor::encode_png(&icon.rgba, icon.width, icon.height).ok()?;
    (png.len() <= MAX_ICON_BYTES).then_some(png)
}

#[cfg(not(windows))]
fn extract(_: &Path) -> Option<Vec<u8>> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_display_metadata_cannot_enter_a_receipt() {
        for name in [String::new(), "x".repeat(97), "Editor\nspoof".into()] {
            assert!(
                !ApplicationDisplay {
                    name,
                    icon_png: None
                }
                .valid()
            );
        }
        assert!(
            !ApplicationDisplay {
                name: "Editor".into(),
                icon_png: Some(vec![0; MAX_ICON_BYTES + 1])
            }
            .valid()
        );
        assert!(
            !ApplicationDisplay {
                name: "Editor".into(),
                icon_png: Some(vec![0; 64])
            }
            .valid()
        );
        let display = ApplicationDisplay {
            name: "😀".repeat(96),
            icon_png: None,
        };
        assert!(display.valid());
        assert_eq!(
            serde_json::from_slice::<ApplicationDisplay>(&serde_json::to_vec(&display).unwrap())
                .unwrap(),
            display
        );
    }
    #[test]
    fn refuses_nonlocal_paths() {
        assert!(png(Path::new("app.exe")).is_none());
        assert!(png(Path::new(r"\\server\share\app.exe")).is_none());
    }
    #[cfg(windows)]
    #[test]
    fn real_executable_icon_is_bounded_and_decodable_with_alpha() {
        let path = std::env::var("WINDIR").unwrap() + r"\explorer.exe";
        let bytes = png(Path::new(&path)).unwrap();
        assert!(bytes.len() <= MAX_ICON_BYTES);
        let image = image::load_from_memory(&bytes).unwrap().into_rgba8();
        assert_eq!(image.dimensions(), (ICON_EDGE, ICON_EDGE));
        assert!(image.pixels().any(|p| p.0[3] != 0));
        assert!(png(Path::new(r"C:\missing-sayin-icon-test.exe")).is_none());
    }
}
