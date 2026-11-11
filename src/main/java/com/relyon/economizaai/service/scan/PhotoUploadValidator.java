package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.function.Function;

import javax.imageio.ImageIO;

/**
 * Shared validation for receipt-photo uploads (QR photo and chave OCR photo).
 * Webp is excluded on purpose: ImageIO can't decode it, and both consumers
 * need actual pixels — unlike profile pictures, there is no store-as-is path.
 *
 * <p>Every decode runs under the shared {@link ImageDecodeGate}. A caller that only needs
 * to know the photo is valid uses {@link #readImage}; a caller that goes on to process the
 * pixels (QR decode, OCR) uses {@link #withDecodedImage}, which keeps the permit — and a
 * single decoded copy of the upload — for the whole processing.
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
    private final ImageDecodeGate imageDecodeGate;

    @Autowired
    public PhotoUploadValidator(@Value("${economizaai.receipt-photo.max-size-mb:5}") int maxSizeMb,
                                ImageDecodeGate imageDecodeGate) {
        this.maxSizeMb = maxSizeMb;
        this.imageDecodeGate = imageDecodeGate;
    }

    /** Stand-alone use (no Spring context): its own gate with the default limits. */
    public PhotoUploadValidator(int maxSizeMb) {
        this(maxSizeMb, new ImageDecodeGate(3, 5000));
    }

    /** Validates and decodes the upload under a decode permit, released before returning. */
    public BufferedImage readImage(MultipartFile file) {
        return imageDecodeGate.withPermit(() -> decode(file));
    }

    /**
     * Validates and decodes the upload ONCE and runs {@code imageWork} on it, all under a
     * single decode permit — for work that needs the pixels (QR decode, OCR passes).
     */
    public <T> T withDecodedImage(MultipartFile file, Function<BufferedImage, T> imageWork) {
        return imageDecodeGate.withPermit(() -> imageWork.apply(decode(file)));
    }

    private BufferedImage decode(MultipartFile file) {
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
