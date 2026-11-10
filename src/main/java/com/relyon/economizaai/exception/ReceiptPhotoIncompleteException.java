package com.relyon.economizaai.exception;

/**
 * Raised at CONFIRM when a photo nota still lacks data a SEFAZ nota always has (date, market,
 * a product name). The message names the first missing field; the review screen shows them all
 * from {@code ReceiptResponse.missingFields}.
 */
public class ReceiptPhotoIncompleteException extends DomainException {

    public ReceiptPhotoIncompleteException(String messageKey, String... arguments) {
        super(messageKey, arguments);
    }
}
