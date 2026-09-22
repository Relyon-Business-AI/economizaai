package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.dto.response.ChaveExtractionResponse;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import com.relyon.economizaai.exception.OcrUnavailableException;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Reads the printed 44-digit chave de acesso off a receipt photo — the
 * fallback for when the QR itself is unreadable (torn, faded, glare).
 * Returns the extracted chave for the FE to show and confirm; it does NOT
 * auto-submit, because OCR can misread even with the check digit guarding
 * (the user must eyeball the digits before submitting).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChaveAcessoOcrService {

    private static final int CHAVE_LENGTH = 44;
    private static final int MIN_OCR_WIDTH_PX = 1200;

    private final PhotoUploadValidator photoUploadValidator;
    private final OcrEngine ocrEngine;

    public ChaveExtractionResponse extractChave(MultipartFile file) {
        if (!ocrEngine.isAvailable()) {
            throw new OcrUnavailableException();
        }
        var image = photoUploadValidator.readImage(file);
        var recognizedText = ocrEngine.recognizeDigits(preprocess(image));
        var chave = findValidChave(recognizedText)
                .orElseThrow(() -> {
                    log.info("chave_ocr.unreadable digits={} size={}x{}",
                            recognizedText.replaceAll("\\D", "").length(), image.getWidth(), image.getHeight());
                    return new InvalidReceiptPhotoException("receipt.photo.chave.unreadable");
                });
        var uf = ChaveAcessoParser.extractUf(chave);
        log.info("chave_ocr.extracted chave={} uf={}", LogMasker.chave(chave), uf);
        return new ChaveExtractionResponse(chave, uf);
    }

    /**
     * Grayscale + upscale small shots: Tesseract wants ~30px glyph height,
     * and the chave is printed small on thermal paper. Binarization itself
     * is left to Tesseract's Otsu pass, which handles receipt lighting
     * better than a fixed threshold.
     */
    private BufferedImage preprocess(BufferedImage source) {
        var scale = source.getWidth() < MIN_OCR_WIDTH_PX
                ? (double) MIN_OCR_WIDTH_PX / source.getWidth()
                : 1.0;
        var targetWidth = (int) Math.round(source.getWidth() * scale);
        var targetHeight = (int) Math.round(source.getHeight() * scale);
        var grayscale = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = grayscale.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return grayscale;
    }

    /**
     * The chave is printed in eleven groups of four digits, often wrapped
     * across two lines, and the OCR text carries other digit runs (totals,
     * CNPJ line). Concatenate every digit and slide a 44-wide window; a
     * window only wins if BOTH the mod-11 check digit and the UF prefix
     * hold, which makes an accidental match across unrelated digit runs
     * practically impossible.
     */
    private Optional<String> findValidChave(String recognizedText) {
        var digits = recognizedText.replaceAll("\\D", "");
        for (var start = 0; start + CHAVE_LENGTH <= digits.length(); start++) {
            var candidate = digits.substring(start, start + CHAVE_LENGTH);
            if (ChaveAcessoParser.hasValidCheckDigit(candidate) && hasKnownUf(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean hasKnownUf(String candidate) {
        try {
            ChaveAcessoParser.extractUf(candidate);
            return true;
        } catch (InvalidQrPayloadException ex) {
            return false;
        }
    }
}
