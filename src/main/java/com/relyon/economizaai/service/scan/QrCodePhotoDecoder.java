package com.relyon.economizaai.service.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Decodes an NFC-e QR code from an uploaded PHOTO (web users with a saved
 * picture, gallery uploads). Live camera scanning stays client-side; this is
 * the server-side fallback for when there is no camera in the loop. The
 * decoded text is opaque here — it feeds the exact same submit flow as a
 * client-side scan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodePhotoDecoder {

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));

    private final PhotoUploadValidator photoUploadValidator;

    public String decode(MultipartFile file) {
        var image = photoUploadValidator.readImage(file);
        var source = new BufferedImageLuminanceSource(image);
        try {
            var result = decodeWithBothBinarizers(source);
            log.info("qr_photo.decoded chars={}", result.getText().length());
            return result.getText();
        } catch (ReaderException ex) {
            log.info("qr_photo.unreadable size={}x{}", image.getWidth(), image.getHeight());
            throw new InvalidReceiptPhotoException("receipt.photo.qr.unreadable");
        }
    }

    /**
     * HybridBinarizer wins on uneven receipt-paper lighting; the global
     * histogram one occasionally rescues flat, low-contrast shots that
     * Hybrid gives up on. Cheap to try both before failing the upload.
     */
    private Result decodeWithBothBinarizers(LuminanceSource source) throws ReaderException {
        try {
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)), HINTS);
        } catch (ReaderException firstAttempt) {
            return new MultiFormatReader().decode(new BinaryBitmap(new GlobalHistogramBinarizer(source)), HINTS);
        }
    }
}
