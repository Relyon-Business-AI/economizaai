package com.relyon.economizaai.exception;

/** Photo item-extraction requested but the LLM layer is disabled, unconfigured, or failed. */
public class PhotoExtractionUnavailableException extends DomainException {

    public PhotoExtractionUnavailableException() {
        super("receipt.photo.extraction.unavailable");
    }
}
