use image::{DynamicImage, ImageFormat};
use std::{
    io::Cursor,
    time::{Duration, Instant},
};
use uuid::Uuid;
use voicelink_receiver::{
    images::{
        ImageMetadata, ImageMime, MAX_CHUNK_BYTES, digest,
        storage::ImageStore,
        wire::{Upload, UploadScope, encode_chunk},
    },
    ledger::Protector,
    windows_input::Dpapi,
};

const ID: &str = "12345678-1234-4234-8234-123456789abc";
fn scope() -> UploadScope {
    UploadScope {
        device: Uuid::new_v4().to_string(),
        session: Uuid::new_v4().to_string(),
        target: "foreground-scope".into(),
        revision: "2".into(),
    }
}
fn metadata(bytes: &[u8]) -> ImageMetadata {
    ImageMetadata {
        bytes: bytes.len() as u32,
        mime: ImageMime::Png,
        width: 3,
        height: 2,
        sha256: digest(bytes),
    }
}
fn png() -> Vec<u8> {
    let mut result = Cursor::new(Vec::new());
    DynamicImage::new_rgba8(3, 2)
        .write_to(&mut result, ImageFormat::Png)
        .unwrap();
    result.into_inner()
}

#[test]
fn binary_chunk_matches_the_cross_language_golden_bytes() {
    let frame = encode_chunk(ID, 0, &[1, 2, 3]).unwrap();
    let hex: String = frame.iter().map(|b| format!("{b:02x}")).collect();
    assert_eq!(
        hex,
        "53494d4712345678123442348234123456789abc000000000000000000000003010203"
    );
    let now = Instant::now();
    let scope = scope();
    let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
    assert_eq!(upload.push(&scope, &frame, now).unwrap(), 3);
    assert_eq!(&**upload.finish(&scope, now).unwrap(), &[1, 2, 3]);
}

#[test]
fn sequential_chunks_are_bounded_and_do_not_require_whole_image_frames() {
    let now = Instant::now();
    let scope = scope();
    let bytes = vec![7; MAX_CHUNK_BYTES * 2 + 3];
    let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&bytes), now).unwrap();
    for (index, chunk) in bytes.chunks(MAX_CHUNK_BYTES).enumerate() {
        upload
            .push(
                &scope,
                &encode_chunk(ID, (index * MAX_CHUNK_BYTES) as u64, chunk).unwrap(),
                now,
            )
            .unwrap();
    }
    assert_eq!(upload.received(), bytes.len());
    assert_eq!(&**upload.finish(&scope, now).unwrap(), bytes);
    assert!(encode_chunk(ID, 0, &vec![0; MAX_CHUNK_BYTES + 1]).is_err());
    assert!(encode_chunk(ID, u64::MAX, &[0]).is_err());
}

#[test]
fn wrong_header_offset_length_identity_and_repeated_chunks_abort_upload() {
    let now = Instant::now();
    let scope = scope();
    let valid = encode_chunk(ID, 0, &[1, 2, 3]).unwrap();
    for index in [0, 4, 20, 27, 28, 31] {
        let mut frame = valid.clone();
        frame[index] ^= 1;
        let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
        assert!(upload.push(&scope, &frame, now).is_err());
        assert_eq!(upload.received(), 0);
        assert!(upload.push(&scope, &valid, now).is_err());
    }
    for size in [0, 4, 20, 31, 32, 34] {
        let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
        assert!(upload.push(&scope, &valid[..size], now).is_err());
    }
    let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
    upload.push(&scope, &valid, now).unwrap();
    assert!(upload.push(&scope, &valid, now).is_err());
    assert!(upload.finish(&scope, now).is_err());
}

#[test]
fn changed_session_device_target_or_revision_cannot_continue_old_upload() {
    let now = Instant::now();
    let scope = scope();
    let mut alternatives = Vec::new();
    let mut other = scope.clone();
    other.session = Uuid::new_v4().to_string();
    alternatives.push(other);
    let mut other = scope.clone();
    other.device = Uuid::new_v4().to_string();
    alternatives.push(other);
    let mut other = scope.clone();
    other.target = "new-target".into();
    alternatives.push(other);
    let mut other = scope.clone();
    other.revision = "3".into();
    alternatives.push(other);
    for other in alternatives {
        let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
        assert!(
            upload
                .push(&other, &encode_chunk(ID, 0, &[1, 2, 3]).unwrap(), now)
                .is_err()
        );
        assert!(upload.finish(&scope, now).is_err());
    }
}

#[test]
fn idle_and_absolute_deadlines_and_incomplete_or_wrong_digest_are_not_ready() {
    let now = Instant::now();
    let scope = scope();
    let upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
    assert!(upload.finish(&scope, now).is_err());
    let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
    assert!(
        upload
            .push(
                &scope,
                &encode_chunk(ID, 0, &[1, 2, 3]).unwrap(),
                now + Duration::from_secs(60)
            )
            .is_err()
    );
    let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&[1, 2, 3]), now).unwrap();
    upload
        .push(&scope, &encode_chunk(ID, 0, &[3, 2, 1]).unwrap(), now)
        .unwrap();
    assert!(upload.finish(&scope, now).is_err());
    let bytes = vec![1; 7];
    let mut upload = Upload::new(ID.into(), scope.clone(), metadata(&bytes), now).unwrap();
    for index in 0..6 {
        upload
            .push(
                &scope,
                &encode_chunk(ID, index, &[1]).unwrap(),
                now + Duration::from_secs(index * 50),
            )
            .unwrap();
    }
    assert!(
        upload
            .push(
                &scope,
                &encode_chunk(ID, 6, &[1]).unwrap(),
                now + Duration::from_secs(300)
            )
            .is_err()
    );
}

#[test]
fn metadata_must_describe_the_actual_supported_image_not_a_claimed_mime() {
    let bytes = png();
    let meta = metadata(&bytes);
    meta.verify_image(&bytes).unwrap();
    let prepared = meta.prepare_image(&bytes).unwrap();
    assert_eq!((prepared.width, prepared.height), (meta.width, meta.height));
    let mut wrong = meta.clone();
    wrong.mime = ImageMime::Jpeg;
    assert!(wrong.verify_image(&bytes).is_err());
    assert!(wrong.prepare_image(&bytes).is_err());
    let mut wrong = meta.clone();
    wrong.width += 1;
    assert!(wrong.verify_image(&bytes).is_err());
    assert!(wrong.prepare_image(&bytes).is_err());
    let mut wrong = meta.clone();
    wrong.sha256 = wrong.sha256.to_uppercase();
    assert!(wrong.validate().is_err());
    let mut wrong = meta.clone();
    wrong.bytes = 10 * 1024 * 1024 + 1;
    assert!(wrong.validate().is_err());
    let mut wrong = meta.clone();
    wrong.width = u32::MAX;
    wrong.height = u32::MAX;
    assert!(wrong.validate().is_err());
    assert!(metadata(&[1, 2, 3]).verify_image(&[1, 2, 3]).is_err());
    // A valid header is insufficient: a damaged compressed payload must fail
    // both persistence validation and clipboard preparation, even with a new hash.
    let mut damaged = bytes.clone();
    let idat = damaged.windows(4).position(|part| part == b"IDAT").unwrap();
    damaged[idat + 4] ^= 0xff; // corrupt the zlib stream, retain the full header/container
    let mut corrupt = meta;
    corrupt.sha256 = digest(&damaged);
    assert!(corrupt.verify_image(&damaged).is_err());
    assert!(corrupt.prepare_image(&damaged).is_err());
}

#[test]
fn protected_files_survive_reopen_and_reject_wrong_scope_tamper_and_substitution() {
    let directory = std::env::temp_dir().join(format!("sayin-image-store-{}", Uuid::new_v4()));
    let host = Uuid::new_v4().to_string();
    let device = Uuid::new_v4().to_string();
    let store = ImageStore::open(&directory, &host).unwrap();
    let bytes = png();
    let resource = store
        .write(&device, metadata(&bytes), &bytes, &Dpapi)
        .unwrap();
    let path = directory.join(format!("{}.image", resource.resource_id));
    let encrypted = std::fs::read(&path).unwrap();
    assert!(!encrypted.windows(bytes.len()).any(|chunk| chunk == bytes));
    let reopened = ImageStore::open(&directory, &host).unwrap();
    assert_eq!(&**reopened.read(&device, &resource, &Dpapi).unwrap(), bytes);
    assert!(
        reopened
            .read(&Uuid::new_v4().to_string(), &resource, &Dpapi)
            .is_err()
    );
    let other_host = ImageStore::open(&directory, &Uuid::new_v4().to_string()).unwrap();
    assert!(other_host.read(&device, &resource, &Dpapi).is_err());
    let other = store
        .write(&device, metadata(&bytes), &bytes, &Dpapi)
        .unwrap();
    std::fs::write(
        &path,
        std::fs::read(directory.join(format!("{}.image", other.resource_id))).unwrap(),
    )
    .unwrap();
    assert!(store.read(&device, &resource, &Dpapi).is_err());
    let mut corrupt = encrypted.clone();
    let index = corrupt.len() / 2;
    corrupt[index] ^= 1;
    std::fs::write(&path, &corrupt).unwrap();
    assert!(Dpapi.unprotect(&corrupt).is_err());
    assert!(store.read(&device, &resource, &Dpapi).is_err());
    std::fs::write(&path, encrypted).unwrap();
    std::fs::write(
        directory.join("interrupted-temporary-file"),
        b"ciphertext fragment",
    )
    .unwrap();
    store
        .clean_orphans(|id| Ok(id == resource.resource_id))
        .unwrap();
    assert!(path.exists());
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
    store.remove(&resource.resource_id).unwrap();
    assert!(store.read(&device, &resource, &Dpapi).is_err());
    assert!(store.remove("../not-an-image").is_err());
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn transient_resource_ownership_bounds_and_cleans_canceled_blocking_results() {
    let directory = std::env::temp_dir().join(format!("sayin-image-owners-{}", Uuid::new_v4()));
    let scope = scope();
    let store = ImageStore::open(&directory, &Uuid::new_v4().to_string()).unwrap();
    let bytes = png();
    let first = store
        .prepare(&scope.device, metadata(&bytes), &bytes, &Dpapi)
        .unwrap();
    let second = store
        .prepare(&scope.device, metadata(&bytes), &bytes, &Dpapi)
        .unwrap();
    assert!(
        store
            .prepare(&scope.device, metadata(&bytes), &bytes, &Dpapi)
            .is_err()
    );
    let retained = first.clone();
    let id = first.image().resource_id.clone();
    drop(first);
    assert!(store.contains(&id).unwrap());
    drop(retained);
    drop(second);
    let until = Instant::now() + Duration::from_secs(2);
    while std::fs::read_dir(&directory).unwrap().next().is_some() && Instant::now() < until {
        std::thread::yield_now();
    }
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
    let worker_store = store.clone();
    let device = scope.device.clone();
    // A canceled awaiting future drops the blocking job's returned owner.
    let result = std::thread::spawn(move || {
        worker_store
            .prepare(&device, metadata(&bytes), &bytes, &Dpapi)
            .unwrap()
    })
    .join()
    .unwrap();
    let id = result.image().resource_id.clone();
    drop(result);
    let until = Instant::now() + Duration::from_secs(2);
    while store.contains(&id).unwrap() && Instant::now() < until {
        std::thread::yield_now();
    }
    assert!(!store.contains(&id).unwrap());
    drop(store);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn cleanup_failure_blocks_further_resource_creation() {
    let directory =
        std::env::temp_dir().join(format!("sayin-image-cleanup-failure-{}", Uuid::new_v4()));
    let scope = scope();
    let store = ImageStore::open(&directory, &Uuid::new_v4().to_string()).unwrap();
    let bytes = png();
    let resource = store
        .prepare(&scope.device, metadata(&bytes), &bytes, &Dpapi)
        .unwrap();
    let path = directory.join(format!("{}.image", resource.image().resource_id));
    std::fs::remove_file(&path).unwrap();
    std::fs::create_dir(&path).unwrap();
    drop(resource);
    let until = Instant::now() + Duration::from_secs(2);
    while store.transient_count() != 0 && Instant::now() < until {
        std::thread::yield_now();
    }
    assert_eq!(store.transient_count(), 0);
    assert!(matches!(
        store.prepare(&scope.device, metadata(&bytes), &bytes, &Dpapi),
        Err(voicelink_receiver::Error::Protocol("IMAGE_STORAGE_BLOCKED"))
    ));
    drop(store);
    std::fs::remove_dir_all(directory).unwrap();
}
