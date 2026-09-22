package com.relyon.economizaai.service.scan;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the REAL native Tesseract when it's installed on the host (dev
 * machines, dev server) and silently skips elsewhere (CI without the
 * native lib) — the pure extraction logic is covered engine-free in
 * {@link ChaveAcessoOcrServiceTest}.
 */
class TesseractOcrEngineTest {

    private static final String CHAVE_VALID_DV = "43260412345678000190650010000123451123456782";

    private final TesseractOcrEngine engine = new TesseractOcrEngine("");

    private static BufferedImage renderedChave(String chave) {
        // Approximates the DANFE print: groups of four, dark digits on paper.
        var grouped = chave.replaceAll("(\\d{4})", "$1 ").trim();
        var image = new BufferedImage(1400, 120, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 40));
        graphics.drawString(grouped, 20, 75);
        graphics.dispose();
        return image;
    }

    @Test
    void recognizeDigits_readsRenderedChave() {
        assumeTrue(engine.isAvailable(), "native Tesseract not installed — skipping");

        var recognized = engine.recognizeDigits(renderedChave(CHAVE_VALID_DV)).replaceAll("\\D", "");

        assertEquals(CHAVE_VALID_DV, recognized);
    }

    @Test
    void fullPipeline_extractsChaveFromRenderedPhoto() throws Exception {
        assumeTrue(engine.isAvailable(), "native Tesseract not installed — skipping");

        var service = new ChaveAcessoOcrService(new PhotoUploadValidator(5), engine);
        var photoBytes = PhotoUploadValidatorTest.pngBytes(renderedChave(CHAVE_VALID_DV));
        var photo = new MockMultipartFile("file", "chave.png", "image/png", photoBytes);

        assertEquals(CHAVE_VALID_DV, service.extractChave(photo).chaveAcesso());
    }

    @Test
    void isAvailable_falseForBogusConfiguredPath() {
        var misconfigured = new TesseractOcrEngine("/nonexistent/tessdata");
        assertFalse(misconfigured.isAvailable());
    }
}
