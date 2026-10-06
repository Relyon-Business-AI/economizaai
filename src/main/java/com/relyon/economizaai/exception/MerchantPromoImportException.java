package com.relyon.economizaai.exception;

public class MerchantPromoImportException extends DomainException {

    public MerchantPromoImportException() {
        super("merchant.promo.import.invalid_file");
    }
}
