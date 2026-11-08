package com.relyon.economizaai.exception;

/**
 * Raised at CONFIRM when a photo-extracted receipt still has a non-excluded item whose price
 * the model couldn't read (total_price null). The user must set the price or remove the item
 * before confirming — we never persist a confirmed line without a price. Carries the count so
 * the message can say how many are missing.
 */
public class ReceiptHasUnpricedItemsException extends DomainException {

    public ReceiptHasUnpricedItemsException(int unpricedCount) {
        super("receipt.confirm.unpriced-items", String.valueOf(unpricedCount));
    }
}
