//! Display-only, bounded sampling. No ledger writes or input authorization.
use super::*;
use base64::engine::general_purpose::STANDARD;
use voicelink_receiver::application_icon::ApplicationDisplay;

#[derive(Clone, Debug)]
pub(super) struct Sample {
    pub application: Option<ApplicationDisplay>,
    observed: Instant,
}
impl Default for Sample {
    fn default() -> Self {
        Self {
            application: None,
            observed: Instant::now(),
        }
    }
}
impl Sample {
    fn fresh(&self) -> bool {
        self.observed.elapsed() < Duration::from_secs(2)
    }
    fn visible(&self, enabled: bool) -> Option<ApplicationDisplay> {
        (self.fresh() && enabled)
            .then(|| self.application.clone())
            .flatten()
    }
}

pub(super) async fn monitor(shared: Arc<Shared>, mut stop: watch::Receiver<bool>) {
    let mut clock = tokio::time::interval(Duration::from_millis(500));
    clock.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    #[cfg(windows)]
    let mut native = voicelink_receiver::windows_input::ForegroundApplication::default();
    loop {
        tokio::select! {
            _ = stop.changed() => return,
            _ = clock.tick() => {}
        }
        // Only authenticated control sessions subscribe. No polling for an idle
        // background phone, and one native query at a time for the whole service.
        if shared.foreground.receiver_count() == 0 {
            continue;
        }
        #[cfg(windows)]
        {
            let task = tokio::task::spawn_blocking(move || {
                let application = native.sample();
                (
                    native,
                    Sample {
                        application,
                        observed: Instant::now(),
                    },
                )
            });
            let result = tokio::select! { _ = stop.changed() => return, result = task => result };
            let Ok((next, sample)) = result else {
                return;
            };
            native = next;
            shared.foreground.send_replace(sample);
        }
        #[cfg(not(windows))]
        shared.foreground.send_replace(Sample::default());
    }
}

pub(super) struct Display {
    connected: Instant,
    sequence: u64,
    application: Option<ApplicationDisplay>,
}
impl Default for Display {
    fn default() -> Self {
        Self {
            connected: Instant::now(),
            sequence: 0,
            application: None,
        }
    }
}
impl Display {
    fn visible(&self, sample: &Sample, enabled: bool) -> Option<ApplicationDisplay> {
        sample.visible(enabled && sample.observed > self.connected)
    }
    pub async fn update(
        &mut self,
        socket: &mut Socket,
        receiver: &Receiver,
        session: &Session,
        sample: &Sample,
        control: &ControlState,
        initial: bool,
    ) -> Result<()> {
        let application = self.visible(
            sample,
            !control.paused && control.active_device_id.as_deref() == Some(session.device()),
        );
        if let Some(value) = self.change(application, session.id(), initial) {
            receiver.check_session(session.clone()).await?;
            send(socket, &value).await?;
        }
        Ok(())
    }
    fn change(
        &mut self,
        application: Option<ApplicationDisplay>,
        session_id: &str,
        initial: bool,
    ) -> Option<Value> {
        if !initial && application == self.application {
            return None;
        }
        self.sequence += 1;
        self.application = application;
        let visible = self
            .application
            .as_ref()
            .filter(|app| app.name.chars().count() <= 96);
        let icon_png = visible
            .and_then(|app| app.icon_png.as_ref())
            .map(|png| STANDARD.encode(png));
        let mut frame = json!({
            "v": 2,
            "t": "foreground",
            "session_id": session_id,
            "seq": self.sequence.to_string(),
            "name": visible.map(|app| &app.name),
            "icon_png": icon_png,
        });
        if !frame["icon_png"].is_null() && frame.to_string().len() > MAX_OUTBOUND_BYTES {
            frame["icon_png"] = Value::Null;
        }
        Some(frame)
    }
    pub fn confirmation(
        &self,
        sample: &Sample,
        control: &ControlState,
        session: &Session,
    ) -> Option<String> {
        (sample.fresh()
            && self.application
                == self.visible(
                    sample,
                    !control.paused
                        && control.active_device_id.as_deref() == Some(session.device()),
                ))
        .then(|| self.sequence.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn app(name: &str) -> ApplicationDisplay {
        ApplicationDisplay {
            name: name.into(),
            icon_png: None,
        }
    }
    #[test]
    fn names_are_deduplicated_and_wire_payload_contains_only_display_metadata() {
        let mut display = Display::default();
        assert_eq!(display.change(None, "session", true).unwrap()["seq"], "1");
        let value = display.change(Some(app("Word")), "session", false).unwrap();
        assert_eq!(
            value,
            json!({"v":2,"t":"foreground","session_id":"session","seq":"2","name":"Word","icon_png":null})
        );
        for _ in 0..120 {
            assert!(
                display
                    .change(Some(app("Word")), "session", false)
                    .is_none()
            );
        }
        assert_eq!(display.change(None, "session", false).unwrap()["seq"], "3");
        assert!(display.change(None, "session", false).is_none());
        assert_eq!(
            Display::default()
                .change(Some(app("Browser")), "new-session", true)
                .unwrap()["seq"],
            "1"
        );
    }
    #[test]
    fn paused_or_inactive_sessions_cannot_display_a_previous_name() {
        let sample = Sample {
            application: Some(app("Word")),
            observed: Instant::now(),
        };
        assert_eq!(sample.visible(true), Some(app("Word")));
        assert!(sample.visible(false).is_none());
    }
    #[test]
    fn same_name_icon_changes_are_atomic_and_missing_icons_clear_the_previous_one() {
        let mut display = Display::default();
        let mut application = app("Terminal");
        application.icon_png = Some(vec![1, 2, 3]);
        let value = display
            .change(Some(application.clone()), "session", true)
            .unwrap();
        assert_eq!(value["icon_png"], "AQID");
        assert!(
            display
                .change(Some(application.clone()), "session", false)
                .is_none()
        );
        application.icon_png = Some(vec![4, 5, 6]);
        assert_eq!(
            display.change(Some(application), "session", false).unwrap()["icon_png"],
            "BAUG"
        );
        assert!(
            display
                .change(Some(app("Terminal")), "session", false)
                .unwrap()["icon_png"]
                .is_null()
        );
        let cleared = display.change(None, "session", false).unwrap();
        assert!(cleared["name"].is_null() && cleared["icon_png"].is_null());
    }
    #[test]
    fn foreground_keeps_the_icon_at_8192_bytes_and_omits_it_above_the_limit() {
        const SESSION: &str = "a9c265c2-7819-4866-a3d8-31c090f6fc25";
        for length in [8_192, 8_193, 8_885] {
            let mut application = app("Baseline Editor");
            let mut unbounded = json!({
                "v": 2, "t": "foreground", "session_id": SESSION, "seq": "1",
                "name": application.name, "icon_png": ""
            });
            let base = serde_json::to_vec(&unbounded).unwrap().len();
            let name_padding = (length - base) % 4;
            application.name.push_str(&"x".repeat(name_padding));
            let icon = vec![42; (length - base - name_padding) / 4 * 3];
            unbounded["name"] = json!(&application.name);
            unbounded["icon_png"] = json!(STANDARD.encode(&icon));
            assert_eq!(serde_json::to_vec(&unbounded).unwrap().len(), length);
            application.icon_png = Some(icon);
            let name = application.name.clone();
            let frame = Display::default()
                .change(Some(application), SESSION, true)
                .unwrap();
            let actual = serde_json::to_vec(&frame).unwrap().len();
            assert_eq!(frame["name"], name);
            assert_eq!(frame["seq"], "1");
            if length == 8_192 {
                assert_eq!(frame, unbounded);
            } else {
                assert!(actual <= 8_192, "foreground serialized to {actual} bytes");
                assert!(frame["icon_png"].is_null());
            }
        }
    }
    #[test]
    fn foreground_discards_an_overlong_application_without_losing_the_observation() {
        let frame = Display::default()
            .change(Some(app(&"😀".repeat(97))), "session", true)
            .unwrap();
        assert_eq!(frame["seq"], "1");
        assert!(frame["name"].is_null() && frame["icon_png"].is_null());
    }
    #[test]
    fn observation_expires_even_if_native_query_stalls() {
        let sample = Sample {
            application: Some(app("Word")),
            observed: Instant::now() - Duration::from_secs(3),
        };
        assert!(!sample.fresh());
        assert!(Sample::default().fresh());
        let previous_connection = Sample {
            application: Some(app("Old app")),
            observed: Instant::now(),
        };
        let new_connection = Display::default();
        assert!(previous_connection.fresh());
        assert!(new_connection.visible(&previous_connection, true).is_none());
    }
}
