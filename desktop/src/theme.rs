//! Audited product roles: the exact brand color, with Yue's neutral surfaces.
//! No colored success/warning/danger variants are used by this application.
use yue_component::{
    Accent, NeutralTone, ThemeMode, YueTheme,
    gpui_kit::{self, App, Hsla},
};

pub const BRAND: u32 = 0x1f75db;

pub fn yue(mode: ThemeMode) -> YueTheme {
    YueTheme::new(mode, Accent::Neutral).with_neutral(NeutralTone::Neutral)
}

pub fn brand() -> Hsla {
    gpui_kit::rgb(BRAND).into()
}

pub fn install(mode: ThemeMode, cx: &mut App) {
    yue_component::set_theme(cx, yue(mode));
    // Base focus and selection use product roles; Yue controls retain their
    // neutral token states until Yue exposes a complete custom-accent API.
    let base = gpui_kit::base::Theme::global_mut(cx);
    base.tokens.colors.primary = brand();
    base.tokens.colors.ring = brand();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn application_surfaces_and_text_use_neutral_in_both_modes() {
        for mode in [ThemeMode::Light, ThemeMode::Dark] {
            let theme = yue(mode);
            for color in [
                theme.page(),
                theme.surface(),
                theme.surface_component(),
                theme.text_primary(),
                theme.text_secondary(),
                theme.border_default(),
            ] {
                assert_eq!((color >> 16) & 255, (color >> 8) & 255);
                assert_eq!((color >> 8) & 255, color & 255);
            }
        }
        assert_eq!(BRAND, 0x1f75db);
    }
}
