package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.MercadoPagoWebhookRequest;
import com.relyon.economizaai.exception.InvalidWebhookSecretException;
import com.relyon.economizaai.service.subscription.MercadoPagoSignatureVerifier;
import com.relyon.economizaai.service.subscription.MercadoPagoWebhookService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mercado Pago subscription webhook — keeps the web-checkout entitlement in
 * sync with our PRO tier. Authenticated by MP's {@code x-signature} HMAC
 * (secret from the MP dashboard); fail-closed when unconfigured. The event id
 * arrives redundantly in the body and the {@code data.id} query param — body
 * wins. Returns 200 on accepted events so MP stops retrying (unknown users /
 * unhandled types are logged no-ops inside the service).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Mercado Pago subscription webhook (web checkout)")
public class MercadoPagoWebhookController {

    private final MercadoPagoWebhookService mercadoPagoWebhookService;
    private final MercadoPagoSignatureVerifier signatureVerifier;

    @PostMapping("/mercadopago")
    public ResponseEntity<Void> mercadopago(
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId,
            @RequestParam(value = "data.id", required = false) String dataIdParam,
            @RequestParam(value = "type", required = false) String typeParam,
            @RequestBody(required = false) MercadoPagoWebhookRequest request) {
        var dataId = request != null && request.data() != null && request.data().id() != null
                ? request.data().id()
                : dataIdParam;
        var type = request != null && request.type() != null ? request.type() : typeParam;
        if (!signatureVerifier.isValid(xSignature, xRequestId, dataId)) {
            throw new InvalidWebhookSecretException();
        }
        mercadoPagoWebhookService.handle(type, dataId);
        return ResponseEntity.ok().build();
    }
}
