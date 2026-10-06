package com.relyon.economizaai.exception;

public class MerchantPromoNotFoundException extends DomainException {

    public MerchantPromoNotFoundException() {
        super("merchant.promo.not.found");
    }
}
