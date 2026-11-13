use uuid::Uuid;
use voicelink_receiver::protocol::*;

fn frame(text: &str) -> Vec<u8> {
    serde_json::to_vec(&Submit::new(
        Uuid::new_v4().to_string(),
        Uuid::new_v4().to_string(),
        Uuid::new_v4().to_string(),
        Operation::Text {
            text: text.into(),
            src: TextSource::Ime,
        },
    ))
    .unwrap()
}

#[test]
fn rejects_duplicate_keys_trailing_documents_deep_values_and_invalid_unicode() {
    for bytes in [
        br#"{"v":2,"v":1}"#.to_vec(),
        br#"{"x":{"a":1,"a":2}}"#.to_vec(),
        br#"{}{}"#.to_vec(),
        br#"{"text":"\uD800"}"#.to_vec(),
        br#"{"text":"\uDC00"}"#.to_vec(),
        [b'{', b'"', b'x', b'"', b':', 0xff, b'}'].to_vec(),
        b"[]".to_vec(),
    ] {
        assert!(strict_json(&bytes).is_err());
    }
    let nested = format!("{{\"x\":{}0{}}}", "[".repeat(9), "]".repeat(9));
    assert!(strict_json(nested.as_bytes()).is_err());
}

#[test]
fn normalization_is_explicit_and_keyboard_words_remain_text() {
    assert_eq!(normalize_text("a\r\nb\tc\nd").unwrap(), "a b c d");
    assert!(normalize_text("a\x1bb").is_err());
    assert!(Submit::decode(&frame("a\nb")).is_err());
    let submit = Submit::decode(&frame("中文😀{ENTER}")).unwrap();
    assert!(matches!(submit.op, Operation::Text { .. }));
}

#[test]
fn unicode_scalar_and_utf8_and_escaped_json_limits_are_independent() {
    assert!(Submit::decode(&frame(&"😀".repeat(4000))).is_ok());
    assert!(Submit::decode(&frame(&"😀".repeat(4001))).is_err());
    assert!(Submit::decode(&frame(&"x".repeat(4001))).is_err());
    let mut value: serde_json::Value = serde_json::from_slice(&frame("ok")).unwrap();
    value["optional"] = serde_json::Value::String("x".repeat(MAX_SUBMIT_BYTES));
    assert!(Submit::decode(&serde_json::to_vec(&value).unwrap()).is_err());
}

#[test]
fn canonical_payload_ignores_session_and_json_field_order_but_includes_target() {
    let first = Submit::decode(&frame("hello")).unwrap();
    let mut second = first.clone();
    second.session_id = Uuid::new_v4().to_string();
    assert_eq!(first.canonical_payload(), second.canonical_payload());
    second.target = Uuid::new_v4().to_string();
    assert_ne!(first.canonical_payload(), second.canonical_payload());
    let mut invalid = first;
    invalid.id = invalid.id.to_uppercase();
    assert!(invalid.validate().is_err());
}
