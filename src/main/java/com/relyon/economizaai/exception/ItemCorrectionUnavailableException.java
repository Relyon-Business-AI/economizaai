package com.relyon.economizaai.exception;

/** The AI layer that re-classifies a corrected item is off, over budget or unreachable right now. */
public class ItemCorrectionUnavailableException extends DomainException {

    public ItemCorrectionUnavailableException() {
        super("item.correction.unavailable");
    }
}
