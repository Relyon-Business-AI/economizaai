package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
}
