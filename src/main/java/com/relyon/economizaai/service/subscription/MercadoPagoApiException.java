package com.relyon.economizaai.service.subscription;

/** Mercado Pago API failure (non-2xx, empty or unparseable payload). */
public class MercadoPagoApiException extends RuntimeException {

    public MercadoPagoApiException(String message) {
        super(message);
    }
}
