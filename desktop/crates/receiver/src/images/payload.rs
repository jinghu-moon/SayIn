//! Bounded, local-only clipboard preparation. No files, clipboard or input side effects.
use image::{DynamicImage, ImageDecoder, ImageFormat, ImageReader, Limits};
use std::io::Cursor;
use zeroize::Zeroizing;

pub const MAX_ENCODED: usize = super::MAX_IMAGE_BYTES as usize;
const MAX_PIXELS: u64 = super::MAX_IMAGE_PIXELS;
const MAX_SIDE: u32 = super::MAX_IMAGE_SIDE;
const HEADER: usize = 124;

pub struct PreparedImage {
    pub width: u32,
    pub height: u32,
    pub png: Zeroizing<Vec<u8>>,
    pub dibv5: Zeroizing<Vec<u8>>,
}

fn dimensions(width: u32, height: u32) -> Result<usize, &'static str> {
    if width == 0 || height == 0 || width > MAX_SIDE || height > MAX_SIDE {
        return Err("image_dimensions");
    }
    let pixels = u64::from(width) * u64::from(height);
    if pixels > MAX_PIXELS {
        return Err("image_pixels");
    }
    usize::try_from(pixels * 4).map_err(|_| "image_allocation")
}

pub fn prepare(encoded: &[u8]) -> Result<PreparedImage, &'static str> {
    if encoded.is_empty() || encoded.len() > MAX_ENCODED {
        return Err("image_bytes");
    }
    let format = image::guess_format(encoded).map_err(|_| "image_format")?;
    if !matches!(format, ImageFormat::Png | ImageFormat::Jpeg) {
        return Err("image_format");
    }
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_SIDE);
    limits.max_image_height = Some(MAX_SIDE);
    limits.max_alloc = Some(128 * 1024 * 1024);
    // Dimensions are checked before full decode and before allocating RGBA/DIB.
    let mut reader = ImageReader::with_format(Cursor::new(encoded), format);
    reader.limits(limits.clone());
    let mut decoder = reader.into_decoder().map_err(|_| "image_header")?;
    let (width, height) = decoder.dimensions();
    dimensions(width, height)?;
    if format == ImageFormat::Png {
        let png = image::codecs::png::PngDecoder::with_limits(Cursor::new(encoded), limits)
            .map_err(|_| "image_header")?;
        if png.is_apng().map_err(|_| "image_header")? {
            return Err("image_animation");
        }
    }
    let orientation = decoder.orientation().map_err(|_| "image_orientation")?;
    let mut decoded = DynamicImage::from_decoder(decoder).map_err(|_| "image_decode")?;
    decoded.apply_orientation(orientation);
    let (width, height) = (decoded.width(), decoded.height());
    let byte_count = dimensions(width, height)?;
    let rgba = decoded.to_rgba8();
    drop(decoded);
    let rgba = Zeroizing::new(rgba.into_raw());
    if rgba.len() != byte_count {
        return Err("image_decode");
    }
    // Re-encode clipboard PNG from the same oriented pixels; no source EXIF/GPS.
    let mut png = Zeroizing::new(Vec::new());
    use image::ImageEncoder;
    image::codecs::png::PngEncoder::new(&mut *png)
        .write_image(&rgba, width, height, image::ExtendedColorType::Rgba8)
        .map_err(|_| "image_encode")?;
    let mut dibv5 = Zeroizing::new(vec![0; HEADER + byte_count]);
    let put = |buffer: &mut [u8], offset: usize, value: u32| {
        buffer[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    };
    put(&mut dibv5, 0, HEADER as u32);
    put(&mut dibv5, 4, width);
    put(&mut dibv5, 8, height); // bottom-up for RichEdit/Word interoperability
    dibv5[12..14].copy_from_slice(&1u16.to_le_bytes());
    dibv5[14..16].copy_from_slice(&32u16.to_le_bytes());
    put(&mut dibv5, 16, 3); // BI_BITFIELDS
    put(&mut dibv5, 20, byte_count as u32);
    for (offset, mask) in [(40, 0xff0000), (44, 0xff00), (48, 0xff), (52, 0xff000000)] {
        put(&mut dibv5, offset, mask);
    }
    put(&mut dibv5, 56, 0x73524742); // LCS_sRGB
    put(&mut dibv5, 108, 4); // LCS_GM_IMAGES
    let stride = width as usize * 4;
    for (src_row, dst_row) in rgba
        .chunks_exact(stride)
        .rev()
        .zip(dibv5[HEADER..].chunks_exact_mut(stride))
    {
        for (src, dst) in src_row
            .as_chunks::<4>()
            .0
            .iter()
            .zip(dst_row.as_chunks_mut::<4>().0.iter_mut())
        {
            dst.copy_from_slice(&[src[2], src[1], src[0], src[3]]);
        }
    }
    Ok(PreparedImage {
        width,
        height,
        png,
        dibv5,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{ImageBuffer, Rgba};

    pub fn png() -> Vec<u8> {
        let pixels = ImageBuffer::from_fn(2, 2, |x, y| match (x, y) {
            (0, 0) => Rgba([255u8, 0, 0, 255]),
            (1, 0) => Rgba([10, 20, 30, 128]),
            (0, 1) => Rgba([0, 0, 0, 0]),
            _ => Rgba([0, 255, 0, 255]),
        });
        let mut buffer = Cursor::new(Vec::new());
        DynamicImage::ImageRgba8(pixels)
            .write_to(&mut buffer, ImageFormat::Png)
            .unwrap();
        buffer.into_inner()
    }

    #[test]
    fn transparent_png_and_bottom_up_bgra_are_identical_pixels() {
        let prepared = prepare(&png()).unwrap();
        assert_eq!((prepared.width, prepared.height), (2, 2));
        assert_eq!(
            &prepared.dibv5[124..],
            &[0, 0, 0, 0, 0, 255, 0, 255, 0, 0, 255, 255, 30, 20, 10, 128]
        );
        assert_eq!(
            i32::from_le_bytes(prepared.dibv5[8..12].try_into().unwrap()),
            2
        );
        assert_eq!(
            image::load_from_memory(&prepared.png).unwrap().to_rgba8(),
            image::load_from_memory(&png()).unwrap().to_rgba8()
        );
    }

    #[test]
    fn dimensions_and_size_are_bounded_before_allocation() {
        assert_eq!(dimensions(4000, 4000), Ok(64_000_000));
        for (w, h) in [
            (0, 1),
            (1, 0),
            (8193, 1),
            (4001, 4000),
            (u32::MAX, u32::MAX),
        ] {
            assert!(dimensions(w, h).is_err());
        }
        assert!(prepare(&vec![0; MAX_ENCODED + 1]).is_err());
        assert!(prepare(&[]).is_err());
    }

    #[test]
    fn malformed_and_other_formats_do_not_become_images() {
        for bytes in [
            b"GIF89a".as_slice(),
            b"not a png",
            &png()[..30],
            &[0xff, 0xd8, 0xff, 0xe0],
        ] {
            assert!(prepare(bytes).is_err());
        }
    }

    #[test]
    fn jpeg_is_decoded_then_published_as_png_and_dib() {
        let source = DynamicImage::new_rgb8(3, 2);
        let mut encoded = Cursor::new(Vec::new());
        source.write_to(&mut encoded, ImageFormat::Jpeg).unwrap();
        let prepared = prepare(encoded.get_ref()).unwrap();
        assert_eq!((prepared.width, prepared.height), (3, 2));
        assert_eq!(
            image::guess_format(&prepared.png).unwrap(),
            ImageFormat::Png
        );
        assert_eq!(prepared.dibv5.len(), 124 + 3 * 2 * 4);
    }

    #[test]
    fn jpeg_exif_rotation_is_applied_and_metadata_is_not_copied() {
        let source = DynamicImage::new_rgb8(3, 2);
        let mut encoded = Cursor::new(Vec::new());
        source.write_to(&mut encoded, ImageFormat::Jpeg).unwrap();
        // EXIF TIFF IFD0 orientation=6 (90 degrees clockwise), little endian.
        let exif = b"Exif\0\0II\x2a\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x06\0\0\0\0\0\0\0";
        let mut jpeg = vec![0xff, 0xd8, 0xff, 0xe1];
        jpeg.extend_from_slice(&((exif.len() + 2) as u16).to_be_bytes());
        jpeg.extend_from_slice(exif);
        jpeg.extend_from_slice(&encoded.get_ref()[2..]);
        let prepared = prepare(&jpeg).unwrap();
        assert_eq!((prepared.width, prepared.height), (2, 3));
        assert!(!prepared.png.windows(4).any(|chunk| chunk == b"eXIf"));
    }

    fn crc(bytes: &[u8]) -> u32 {
        let mut value = !0u32;
        for byte in bytes {
            value ^= u32::from(*byte);
            for _ in 0..8 {
                value = (value >> 1) ^ (0xedb88320 & (0u32.wrapping_sub(value & 1)));
            }
        }
        !value
    }

    #[test]
    fn animated_png_is_rejected_instead_of_silently_taking_its_default_image() {
        let original = png();
        let mut animated = original[..33].to_vec(); // signature + IHDR
        let chunk = b"acTL\0\0\0\x01\0\0\0\0";
        animated.extend_from_slice(&8u32.to_be_bytes());
        animated.extend_from_slice(chunk);
        animated.extend_from_slice(&crc(chunk).to_be_bytes());
        animated.extend_from_slice(&original[33..]);
        assert_eq!(prepare(&animated).err(), Some("image_animation"));
    }

    #[test]
    fn valid_png_header_over_pixel_budget_is_rejected_before_idat_decode() {
        let mut malicious = png();
        malicious[16..20].copy_from_slice(&8192u32.to_be_bytes());
        malicious[20..24].copy_from_slice(&8192u32.to_be_bytes());
        let checksum = crc(&malicious[12..29]);
        malicious[29..33].copy_from_slice(&checksum.to_be_bytes());
        assert_eq!(prepare(&malicious).err(), Some("image_pixels"));
    }
}
