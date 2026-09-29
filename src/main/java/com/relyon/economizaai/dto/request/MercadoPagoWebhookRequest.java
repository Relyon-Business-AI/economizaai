package com.relyon.economizaai.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mercado Pago webhook body — only the fields we act on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoWebhookRequest(String type, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String id) {}
}
