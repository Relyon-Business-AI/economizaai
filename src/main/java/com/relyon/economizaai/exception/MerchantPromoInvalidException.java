package com.relyon.economizaai.exception;

/** Takes the full i18n key of the specific validation failure (merchant.promo.invalid.*). */
public class MerchantPromoInvalidException extends DomainException {

    public MerchantPromoInvalidException(String messageKey) {
        super(messageKey);
    }
}
