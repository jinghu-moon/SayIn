//! Synthetic receipts for UI tests only. Never runs an input adapter.
use std::{path::Path, sync::Arc};
use voicelink_receiver::{
    images::{
        ImageAuthorization, ImageMetadata, ImageMime, digest, storage::ImageStore,
        wire::UploadScope,
    },
    ledger::InputOutcome,
    protocol::{Operation, Submit},
    windows_input::{Dpapi, open_ledger},
};
use voicelink_transport::HostIdentity;

pub fn seed(directory: &Path) -> Vec<i64> {
    seed_page(directory, 3)
}

/// Independent, completed receipts fill the production 30-entry cache. The
/// original three-entry fixture retains its final held record.
pub fn seed_page(directory: &Path, count: usize) -> Vec<i64> {
    assert!((3..=30).contains(&count));
    let data = directory.join("receiver");
    std::fs::create_dir_all(&data).unwrap();
    let mut ledger = open_ledger(&data).unwrap();
    let identity = HostIdentity::open(&data, 0).unwrap();
    let store = ImageStore::open(&data.join("images"), identity.host_id()).unwrap();
    ledger.attach_images(store.clone()).unwrap();
    let device = "12345678-1234-4234-8234-123456789abc";
    ledger
        .provision_device(device, &[7; 32], "Isolated image UI phone".into())
        .unwrap();
    let session = ledger.authenticate(device, &[7; 32]).unwrap();
    let state = ledger.bind_target(identity.host_id()).unwrap();
    ledger.resume(&state.state_rev, false).unwrap();
    let portrait = include_bytes!("../tests/fixtures/history-portrait.png").as_slice();
    let landscape = include_bytes!("../tests/fixtures/history-landscape.png").as_slice();
    let small = include_bytes!("../assets/sayin-64.png").as_slice();
    let mut orders = Vec::new();
    for index in 0..count {
        let (bytes, width, height) = [
            (portrait, 1080, 2400),
            (landscape, 2400, 1080),
            (small, 64, 64),
        ][index % 3];
        let completed = count != 3 || index < 2;
        let resource = store
            .prepare(
                device,
                ImageMetadata {
                    bytes: bytes.len() as u32,
                    mime: ImageMime::Png,
                    width,
                    height,
                    sha256: digest(bytes),
                },
                bytes,
                &Dpapi,
            )
            .unwrap();
        let image = resource.image().clone();
        ledger
            .register_image(
                &session,
                UploadScope {
                    device: device.into(),
                    session: session.id().into(),
                    target: identity.host_id().into(),
                    revision: ledger.state().unwrap().state_rev,
                },
                resource,
                Arc::new(ImageAuthorization::new(Arc::new(
                    std::sync::atomic::AtomicBool::new(true),
                ))),
            )
            .unwrap();
        // Test receipts only; finish never calls the Windows adapter.
        let id = format!("{:08x}-1234-4234-8234-123456789abc", index + 1);
        let receipt = ledger
            .submit(
                &session,
                &Submit::new(
                    session.id().into(),
                    id,
                    identity.host_id().into(),
                    Operation::Image { image },
                ),
            )
            .unwrap();
        orders.push(receipt.order.parse::<i64>().unwrap());
        if completed {
            let job = ledger.prepare_next().unwrap().unwrap();
            assert!(ledger.mark_executing(&job).unwrap());
            ledger
                .finish(
                    &job,
                    InputOutcome::Injected {
                        method: "clipboard_image",
                    },
                )
                .unwrap();
        }
    }
    drop(ledger);
    drop(store);
    drop(identity);
    orders
}
