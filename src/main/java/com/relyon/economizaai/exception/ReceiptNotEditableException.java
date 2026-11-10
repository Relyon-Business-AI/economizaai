package com.relyon.economizaai.exception;

import lombok.Getter;

/**
 * The receipt can't be edited in its current state. The machine reason (a status
 * name such as CONFIRMED, or a marker like NOT_PHOTO) is kept for logs only and
 * never shown in the user-facing message.
 */
@Getter
public class ReceiptNotEditableException extends DomainException {

    private final String currentStatus;

    public ReceiptNotEditableException(String currentStatus) {
        super("receipt.not.editable");
        this.currentStatus = currentStatus;
    }
}
