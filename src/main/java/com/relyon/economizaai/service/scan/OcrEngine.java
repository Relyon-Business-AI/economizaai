package com.relyon.economizaai.service.scan;

import java.awt.image.BufferedImage;

/**
 * Digit-only OCR abstraction so the chave-extraction logic is unit-testable
 * without the native Tesseract library installed.
 */
public interface OcrEngine {

    boolean isAvailable();

    /** Raw recognized text (digits and whitespace only). Never null. */
    String recognizeDigits(BufferedImage image);
}
