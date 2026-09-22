package com.relyon.economizaai.exception;

/** A manually-entered paid price is higher than the item's original as-printed price. */
public class InvalidItemPriceException extends DomainException {

    public InvalidItemPriceException() {
        super("receipt.item.paid.price.exceeds.original");
    }
}
