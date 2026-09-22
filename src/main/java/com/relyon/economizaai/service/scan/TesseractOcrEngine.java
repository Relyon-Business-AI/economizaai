package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.OcrUnavailableException;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.stream.Stream;

/**
 * Tess4J-backed OCR (Apache-2.0, free for commercial use). Requires the
 * native Tesseract library + eng traineddata on the host — resolved at
 * first use by probing well-known install locations, overridable via
 * {@code economizaai.ocr.tessdata-path}. When the host has no Tesseract,
 * {@link #isAvailable()} is false and callers surface a localized 503
 * instead of a crash.
 */
@Slf4j
@Component
public class TesseractOcrEngine implements OcrEngine {

    private static final String[] TESSDATA_CANDIDATES = {
            "/opt/homebrew/share/tessdata",
            "/usr/local/share/tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata"
    };
    private static final String[] NATIVE_LIB_DIRS = {"/opt/homebrew/lib", "/usr/local/lib"};

    private final String configuredTessdataPath;
    private volatile Boolean available;
    private volatile String tessdataPath;

    public TesseractOcrEngine(@Value("${economizaai.ocr.tessdata-path:}") String configuredTessdataPath) {
        this.configuredTessdataPath = configuredTessdataPath;
    }

    @Override
    public boolean isAvailable() {
        if (available == null) {
            initialize();
        }
        return available;
    }

    @Override
    public String recognizeDigits(BufferedImage image) {
        if (!isAvailable()) {
            throw new OcrUnavailableException();
        }
        try {
            var recognized = buildTesseract().doOCR(image);
            return recognized == null ? "" : recognized;
        } catch (TesseractException ex) {
            log.warn("ocr.recognize failed {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new OcrUnavailableException();
        }
    }

    private synchronized void initialize() {
        if (available != null) {
            return;
        }
        tessdataPath = resolveTessdataPath();
        if (tessdataPath == null) {
            log.warn("ocr.init failed reason=tessdata_not_found configured='{}' — chave OCR disabled on this host",
                    configuredTessdataPath);
            available = false;
            return;
        }
        pointJnaAtNativeLibIfNeeded();
        try {
            // Smoke-run on a tiny blank image: forces the native lib to load so a
            // missing libtesseract surfaces here (as unavailable) instead of as a
            // 500 on the first real user upload.
            buildTesseract().doOCR(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY));
            available = true;
            log.info("ocr.init ok tessdata={}", tessdataPath);
        } catch (TesseractException | UnsatisfiedLinkError | NoClassDefFoundError ex) {
            log.warn("ocr.init failed reason=native_lib {}: {} — chave OCR disabled on this host",
                    ex.getClass().getSimpleName(), ex.getMessage());
            available = false;
        }
    }

    /**
     * Tess4J's Tesseract instance is not thread-safe — build a fresh one per
     * call (cheap; the heavy state is the traineddata, cached natively).
     */
    private Tesseract buildTesseract() {
        var tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage("eng");
        tesseract.setVariable("tessedit_char_whitelist", "0123456789 ");
        return tesseract;
    }

    private String resolveTessdataPath() {
        if (configuredTessdataPath != null && !configuredTessdataPath.isBlank()) {
            return hasEngTraineddata(configuredTessdataPath) ? configuredTessdataPath : null;
        }
        return Stream.of(TESSDATA_CANDIDATES)
                .filter(this::hasEngTraineddata)
                .findFirst()
                .orElse(null);
    }

    private boolean hasEngTraineddata(String directory) {
        return new File(directory, "eng.traineddata").isFile();
    }

    /**
     * Homebrew's lib dir is not on JNA's default search path on macOS; without
     * this hint Tess4J throws UnsatisfiedLinkError even with Tesseract installed.
     */
    private void pointJnaAtNativeLibIfNeeded() {
        if (System.getProperty("jna.library.path") != null) {
            return;
        }
        Stream.of(NATIVE_LIB_DIRS)
                .filter(directory -> new File(directory, "libtesseract.dylib").isFile()
                        || new File(directory, "libtesseract.so").isFile())
                .findFirst()
                .ifPresent(directory -> System.setProperty("jna.library.path", directory));
    }
}
