//! Background-only history image reads. Never runs from GPUI render or on the
//! ledger coordinator. The caller bounds concurrency and visible cache entries.
use voicelink_receiver::{Error, Result, windows_input::Dpapi, worker::Receiver};
use yue_component::{ImagePreviewImage, ImagePreviewLimits};

pub(crate) async fn bytes(receiver: &Receiver, order: i64) -> Result<zeroize::Zeroizing<Vec<u8>>> {
    let (device, image) = receiver
        .history_image(order)
        .await?
        .ok_or(Error::Protocol("IMAGE_HISTORY_REMOVED"))?;
    receiver
        .image_store()
        .ok_or(Error::Protocol("IMAGE_UNAVAILABLE"))?
        .read(&device, &image, &Dpapi)
}
pub(crate) async fn preview(
    receiver: &Receiver,
    order: i64,
    edge: u32,
) -> Result<ImagePreviewImage> {
    let bytes = bytes(receiver, order).await?;
    let limits = ImagePreviewLimits::default()
        .max_encoded_bytes(10 * 1024 * 1024)
        .max_pixels(16_000_000)
        .max_source_edge(8192)
        .max_decode_bytes(128 * 1024 * 1024)
        .texture_budget(edge, u64::from(edge) * u64::from(edge) * 4);
    ImagePreviewImage::from_encoded(&bytes, &limits, "SayIn image")
        .map_err(|_| Error::Protocol("IMAGE_PREVIEW_FAILED"))
}

pub(crate) async fn copy(
    receiver: &Receiver,
    order: i64,
    owner: &voicelink_receiver::windows_clipboard::ClipboardOwner,
) -> Result<()> {
    let bytes = bytes(receiver, order).await?;
    let image = voicelink_receiver::images::payload::prepare(&bytes).map_err(Error::Protocol)?;
    voicelink_receiver::windows_clipboard::publish(owner.window(), &image, || true).map_err(
        |failure| {
            Error::Protocol(if failure.clipboard_changed {
                "CLIPBOARD_CHANGED_NOT_COPIED"
            } else {
                "IMAGE_COPY_FAILED"
            })
        },
    )?;
    Ok(())
}

pub(crate) async fn save(receiver: &Receiver, order: i64, path: &std::path::Path) -> Result<()> {
    use std::io::Write as _;
    let bytes = bytes(receiver, order).await?;
    let mut output = atomic_write_file::AtomicWriteFile::open(path)?;
    output.write_all(&bytes)?;
    output.sync_all()?;
    output.commit()?;
    Ok(())
}
