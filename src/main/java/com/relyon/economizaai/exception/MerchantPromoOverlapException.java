package com.relyon.economizaai.exception;

public class MerchantPromoOverlapException extends DomainException {

    public MerchantPromoOverlapException(String ean) {
        super("merchant.promo.overlap", ean);
    }
}
