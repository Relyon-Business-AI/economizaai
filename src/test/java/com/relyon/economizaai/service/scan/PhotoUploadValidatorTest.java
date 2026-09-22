package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhotoUploadValidatorTest {

    private final PhotoUploadValidator validator = new PhotoUploadValidator(5);

    static byte[] pngBytes(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void readImage_acceptsValidPng() throws IOException {
        var file = new MockMultipartFile("file", "receipt.png", "image/png",
                pngBytes(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));

        var image = validator.readImage(file);

        assertNotNull(image);
        assertEquals(10, image.getWidth());
    }

    @Test
    void readImage_rejectsEmptyFile() {
        var file = new MockMultipartFile("file", "receipt.png", "image/png", new byte[0]);

        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> validator.readImage(file));
        assertEquals("receipt.photo.empty", exception.getMessageKey());
    }

    @Test
    void readImage_rejectsUnsupportedContentType() {
        var file = new MockMultipartFile("file", "receipt.webp", "image/webp", new byte[]{1, 2, 3});

        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> validator.readImage(file));
        assertEquals("receipt.photo.invalid.type", exception.getMessageKey());
    }

    @Test
    void readImage_rejectsOversizedFile() {
        var oversized = new byte[6 * 1024 * 1024];
        var file = new MockMultipartFile("file", "receipt.png", "image/png", oversized);

        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> validator.readImage(file));
        assertEquals("receipt.photo.too.large", exception.getMessageKey());
    }

    @Test
    void readImage_rejectsUndecodableBytesWithImageContentType() {
        var file = new MockMultipartFile("file", "receipt.png", "image/png", "not an image".getBytes());

        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> validator.readImage(file));
        assertEquals("receipt.photo.invalid.type", exception.getMessageKey());
    }

    @Test
    void readImage_rejectsDecompressionBombBeforeDecoding() throws IOException {
        // A few-hundred-byte PNG whose header declares 40000x40000 px would
        // decode to a multi-GB raster — must be rejected from the header alone.
        var file = new MockMultipartFile("file", "receipt.png", "image/png",
                pngWithForgedDimensions(40_000, 40_000));

        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> validator.readImage(file));
        assertEquals("receipt.photo.dimensions.too.large", exception.getMessageKey());
    }

    /**
     * Takes a real 1x1 PNG and rewrites the IHDR width/height (with a fixed-up
     * CRC), producing a tiny file that DECLARES huge dimensions — the exact
     * shape of a decompression bomb.
     */
    private static byte[] pngWithForgedDimensions(int width, int height) throws IOException {
        var png = pngBytes(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        // Layout: 8-byte signature, 4-byte IHDR length, 4-byte "IHDR",
        // 13 bytes of data (width at offset 16, height at 20), 4-byte CRC.
        writeIntBigEndian(png, 16, width);
        writeIntBigEndian(png, 20, height);
        var crc = new CRC32();
        crc.update(png, 12, 4 + 13); // CRC covers the chunk type + data
        writeIntBigEndian(png, 29, (int) crc.getValue());
        return png;
    }

    private static void writeIntBigEndian(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
