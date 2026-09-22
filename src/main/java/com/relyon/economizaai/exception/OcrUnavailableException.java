package com.relyon.economizaai.exception;

public class OcrUnavailableException extends DomainException {

    public OcrUnavailableException() {
        super("receipt.ocr.unavailable");
    }
}
