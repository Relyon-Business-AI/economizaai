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
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
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
    // Passe "forte" (fallback): upscale maior + unsharp + binarização de limiar
    // fixo. Validado contra fotos degradadas (amassadas/apagadas) subindo a leitura
    // de ~3/15 pra ~12/15 em relação ao passe leve. Só roda quando o leve falha.
    private static final int HARD_OCR_WIDTH_PX = 2400;
    // Passe "médio" só pro best-effort: fotos grandes (3-4 mil px) rodam em full-res
    // no passe leve e saem ruidosas; reduzir pra ~1600 (glifos num tamanho que o
    // Tesseract lê melhor) costuma dar a leitura com menos dígitos errados.
    private static final int MEDIUM_OCR_WIDTH_PX = 1600;
    private static final double UNSHARP_SIGMA = 2.5;
    private static final int UNSHARP_RADIUS = 8;
    private static final float UNSHARP_AMOUNT = 1.0f;
    private static final int BINARIZE_THRESHOLD = 140; // ~55% de 255

    private final PhotoUploadValidator photoUploadValidator;
    private final OcrEngine ocrEngine;
    private final QrCodePhotoDecoder qrCodePhotoDecoder;

    public ChaveExtractionResponse extractChave(MultipartFile file) {
        // QR na foto primeiro: o payload ASSINADO funciona em qualquer estado,
        // enquanto a chave pura (OCR) só onde há consulta pública por chave.
        try {
            var qrPayload = qrCodePhotoDecoder.decode(file);
            var chave = ChaveAcessoParser.extractChave(qrPayload);
            log.info("chave_ocr.qr_in_photo chave={} uf={}", LogMasker.chave(chave), ChaveAcessoParser.extractUf(chave));
            return new ChaveExtractionResponse(chave, ChaveAcessoParser.extractUf(chave), qrPayload);
        } catch (RuntimeException qrMiss) {
            log.debug("chave_ocr.qr_not_found reason={}", qrMiss.getClass().getSimpleName());
        }
        if (!ocrEngine.isAvailable()) {
            throw new OcrUnavailableException();
        }
        var image = photoUploadValidator.readImage(file);
        // Passe leve primeiro (barato, pega a maioria). Só se falhar roda o passe
        // forte, mais caro — ele não regride o leve (roda depois) e o gate
        // DV+UF+modelo em findValidChave garante que nenhum dos dois devolva lixo.
        var primaryText = ocrEngine.recognizeDigits(preprocess(image));
        var chave = findValidChave(primaryText).orElse(null);
        var hardText = chave == null ? ocrEngine.recognizeDigits(preprocessHard(image)) : null;
        if (chave == null) {
            chave = findValidChave(hardText).orElse(null);
            if (chave != null) {
                log.info("chave_ocr.rescued_by_hard_pass");
            }
        }
        if (chave != null) {
            var uf = ChaveAcessoParser.extractUf(chave);
            log.info("chave_ocr.extracted chave={} uf={}", LogMasker.chave(chave), uf);
            return new ChaveExtractionResponse(chave, uf);
        }
        // Nenhuma janela fechou o DV. Tenta um melhor-esforço: a janela mais plausível
        // ESTRUTURALMENTE (UF + modelo + AAMM válidos) cujo DV não fechou — provável erro
        // de 1-2 dígitos. Volta pro usuário CONFERIR/corrigir (uncertain=true); nunca
        // entra sozinha, e o gate de DV no submit impede gastar em chave errada.
        // Palpite: tenta o passe médio (downscale ~1600, costuma ler foto grande com
        // menos erro), depois o forte (2400 binarizado) e por fim o leve.
        var mediumText = ocrEngine.recognizeDigits(scaleToGray(image, MEDIUM_OCR_WIDTH_PX));
        var guess = findBestEffortChave(mediumText)
                .or(() -> findBestEffortChave(hardText))
                .or(() -> findBestEffortChave(primaryText))
                .orElse(null);
        if (guess != null) {
            var uf = ChaveAcessoParser.extractUf(guess);
            log.info("chave_ocr.best_effort chave={} uf={}", LogMasker.chave(guess), uf);
            return new ChaveExtractionResponse(guess, uf, null, true);
        }
        log.info("chave_ocr.unreadable digits={} size={}x{}",
                primaryText.replaceAll("\\D", "").length(), image.getWidth(), image.getHeight());
        throw new InvalidReceiptPhotoException("receipt.photo.chave.unreadable");
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
     * Fallback preprocessing for degraded shots (crumpled/faded/low-contrast,
     * where Tesseract's own Otsu pass on the light preprocess misreads): grayscale
     * + larger upscale, an unsharp mask to recover washed-out edges, then a fixed
     * global threshold to a clean bitonal image. Measured to lift reads from ~3/15
     * to ~12/15 on degraded fixtures vs the light pass; a fixed threshold beat Otsu
     * and adaptive thresholding here (uneven lighting + the store watermark throw
     * Otsu's single cut off).
     */
    private BufferedImage preprocessHard(BufferedImage source) {
        return binarize(unsharpMask(scaleToGray(source, HARD_OCR_WIDTH_PX)), BINARIZE_THRESHOLD);
    }

    private BufferedImage scaleToGray(BufferedImage source, int targetWidth) {
        var scale = (double) targetWidth / source.getWidth();
        var targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        var grayscale = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = grayscale.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return grayscale;
    }

    /** out = in + amount·(in − gaussianBlur(in)), clamped — sharpens faded edges. */
    private BufferedImage unsharpMask(BufferedImage gray) {
        var blurred = gaussianBlur(gray);
        var width = gray.getWidth();
        var height = gray.getHeight();
        var sharpened = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var source = gray.getRaster();
        var blur = blurred.getRaster();
        var target = sharpened.getRaster();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var original = source.getSample(x, y, 0);
                var detail = original - blur.getSample(x, y, 0);
                var value = Math.round(original + UNSHARP_AMOUNT * detail);
                target.setSample(x, y, 0, Math.max(0, Math.min(255, value)));
            }
        }
        return sharpened;
    }

    /** Separable Gaussian (two 1-D passes) — far cheaper than a 2-D kernel. */
    private BufferedImage gaussianBlur(BufferedImage gray) {
        var kernel = gaussianKernel(UNSHARP_SIGMA, UNSHARP_RADIUS);
        var horizontal = new ConvolveOp(new Kernel(kernel.length, 1, kernel), ConvolveOp.EDGE_NO_OP, null);
        var vertical = new ConvolveOp(new Kernel(1, kernel.length, kernel), ConvolveOp.EDGE_NO_OP, null);
        var pass = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        horizontal.filter(gray, pass);
        var result = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        vertical.filter(pass, result);
        return result;
    }

    private float[] gaussianKernel(double sigma, int radius) {
        var size = radius * 2 + 1;
        var kernel = new float[size];
        var sum = 0f;
        for (var offset = -radius; offset <= radius; offset++) {
            var weight = (float) Math.exp(-(offset * offset) / (2 * sigma * sigma));
            kernel[offset + radius] = weight;
            sum += weight;
        }
        for (var index = 0; index < size; index++) {
            kernel[index] /= sum;
        }
        return kernel;
    }

    private BufferedImage binarize(BufferedImage gray, int threshold) {
        var width = gray.getWidth();
        var height = gray.getHeight();
        var bitonal = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var source = gray.getRaster();
        var target = bitonal.getRaster();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                target.setSample(x, y, 0, source.getSample(x, y, 0) >= threshold ? 255 : 0);
            }
        }
        return bitonal;
    }

    /**
     * The chave is printed in eleven groups of four digits, often wrapped
     * across two lines, and the OCR text carries other digit runs (totals,
     * CNPJ line). Concatenate every digit and slide a 44-wide window; a
     * window only wins if the mod-11 check digit, the UF prefix AND the whole
     * structural shape (model 65/55, month 01-12, tpEmis 1-9) all hold. The
     * check digit alone is weak — it passes for ~1 in 11 random windows — so on
     * a crumpled/faded shot a garbage window can clear DV by luck; requiring a
     * valid UF and structure makes that window practically impossible (a real
     * OCR miss from 2026 cleared DV with UF 12 but carried model 52, which this
     * gate now rejects).
     */
    private Optional<String> findValidChave(String recognizedText) {
        var digits = recognizedText.replaceAll("\\D", "");
        for (var start = 0; start + CHAVE_LENGTH <= digits.length(); start++) {
            var candidate = digits.substring(start, start + CHAVE_LENGTH);
            if (ChaveAcessoParser.hasValidCheckDigit(candidate)
                    && hasKnownUf(candidate)
                    && ChaveAcessoParser.hasPlausibleStructure(candidate)) {
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

    /**
     * Strongest structurally-plausible 44-window — valid UF plus a valid model (65/55),
     * month (01-12) and tpEmis (1-9) — whose mod-11 digit did NOT close (so findValidChave
     * already rejected it). Returned only as a best-effort guess for the user to review/fix.
     */
    private Optional<String> findBestEffortChave(String recognizedText) {
        if (recognizedText == null) {
            return Optional.empty();
        }
        var digits = recognizedText.replaceAll("\\D", "");
        for (var start = 0; start + CHAVE_LENGTH <= digits.length(); start++) {
            var candidate = digits.substring(start, start + CHAVE_LENGTH);
            if (hasKnownUf(candidate)
                    && ChaveAcessoParser.hasPlausibleStructure(candidate)
                    && !ChaveAcessoParser.hasValidCheckDigit(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
