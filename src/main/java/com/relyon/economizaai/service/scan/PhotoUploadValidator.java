package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Shared validation for receipt-photo uploads (QR photo and chave OCR photo).
 * Webp is excluded on purpose: ImageIO can't decode it, and both consumers
 * need actual pixels — unlike profile pictures, there is no store-as-is path.
 */
@Component
public class PhotoUploadValidator {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/jpg", "image/png");

    /**
     * Cap on DECLARED pixel dimensions, checked from the header before any
     * decode. The byte cap alone doesn't protect the heap: PNG compresses
     * ~1000:1, so a few-hundred-KB file declaring 40000x40000 px would decode
     * to a multi-GB raster and OOM the instance. 25MP comfortably fits any
     * phone camera.
     */
    static final long MAX_PIXELS = 25_000_000L;

    private final int maxSizeMb;

    public PhotoUploadValidator(@Value("${economizaai.receipt-photo.max-size-mb:5}") int maxSizeMb) {
        this.maxSizeMb = maxSizeMb;
    }

    public BufferedImage readImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidReceiptPhotoException("receipt.photo.empty");
        }
        var contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidReceiptPhotoException("receipt.photo.invalid.type");
        }
        var maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new InvalidReceiptPhotoException("receipt.photo.too.large", String.valueOf(maxSizeMb));
        }
        try {
            var bytes = file.getBytes();
            assertDeclaredDimensionsSane(bytes);
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new InvalidReceiptPhotoException("receipt.photo.invalid.type");
            }
            return image;
        } catch (IOException ex) {
            throw new InvalidReceiptPhotoException("receipt.photo.invalid.type");
        }
    }

    /**
     * Reads only the header metadata (no pixel decode) and rejects images whose
     * declared dimensions exceed {@link #MAX_PIXELS}.
     */
    private void assertDeclaredDimensionsSane(byte[] bytes) throws IOException {
        try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new InvalidReceiptPhotoException("receipt.photo.invalid.type");
            }
            var reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                var width = (long) reader.getWidth(0);
                var height = (long) reader.getHeight(0);
                if (width * height > MAX_PIXELS) {
                    throw new InvalidReceiptPhotoException("receipt.photo.dimensions.too.large");
                }
            } finally {
                reader.dispose();
            }
        }
    }
}
