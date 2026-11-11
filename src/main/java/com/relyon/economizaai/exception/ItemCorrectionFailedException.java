package com.relyon.economizaai.exception;

/** The AI answered, but could not tell what the product is from the item text plus the user's hint. */
public class ItemCorrectionFailedException extends DomainException {

    public ItemCorrectionFailedException() {
        super("item.correction.failed");
    }
}
