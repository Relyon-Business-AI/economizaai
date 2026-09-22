package com.relyon.economizai.exception;

public class EcommerceOfferNotFoundException extends DomainException {

    public EcommerceOfferNotFoundException(String offerId) {
        super("ecommerce.offer.not.found", offerId);
    }
}
