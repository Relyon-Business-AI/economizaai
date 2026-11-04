package com.relyon.economizaai.exception;

public class InvalidMerchantSegmentException extends DomainException {

    public InvalidMerchantSegmentException(String segment) {
        super("merchant.segment.invalid", segment);
    }
}
