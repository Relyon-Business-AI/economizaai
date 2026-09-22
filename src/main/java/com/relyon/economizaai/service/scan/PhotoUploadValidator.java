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
            var image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            if (image == null) {
                throw new InvalidReceiptPhotoException("receipt.photo.invalid.type");
            }
            return image;
        } catch (IOException ex) {
            throw new InvalidReceiptPhotoException("receipt.photo.invalid.type");
        }
    }
}
