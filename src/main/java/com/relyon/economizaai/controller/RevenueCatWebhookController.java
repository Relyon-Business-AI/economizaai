package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.RevenueCatWebhookRequest;
import com.relyon.economizaai.exception.InvalidWebhookSecretException;
import com.relyon.economizaai.service.subscription.RevenueCatWebhookService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RevenueCat billing webhook — keeps the apps' subscription entitlement
 * (Apple StoreKit + Google Play Billing, unified by RevenueCat) in sync with
 * our PRO tier. RevenueCat authenticates by sending a fixed string in the
 * {@code Authorization} header that you configure in its dashboard; we check it
 * against {@code economizaai.billing.revenuecat.auth-header} (constant-time).
 *
 * <p>Public route (permitAll in SecurityConfig). When no auth value is
 * configured the webhook is <strong>disabled</strong> — every call is rejected
 * (fail-closed). Always returns 200 for accepted calls so RevenueCat doesn't
 * retry (unknown users are a logged no-op inside the service).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "RevenueCat subscription webhook (Apple/Google IAP)")
public class RevenueCatWebhookController {

    private final RevenueCatWebhookService revenueCatWebhookService;

    @Value("${economizaai.billing.revenuecat.auth-header:}")
    private String authHeaderSecret;

    @PostMapping("/revenuecat")
    public ResponseEntity<Void> revenuecat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) RevenueCatWebhookRequest request) {
        verifyAuthHeader(authHeader);
        revenueCatWebhookService.handle(request != null ? request.event() : null);
        return ResponseEntity.ok().build();
    }

    private void verifyAuthHeader(String provided) {
        if (authHeaderSecret == null || authHeaderSecret.isBlank()) {
            log.warn("revenuecat.webhook.auth not_configured rejecting");
            throw new InvalidWebhookSecretException();
        }
        var providedValue = provided != null ? provided : "";
        if (!MessageDigest.isEqual(
                authHeaderSecret.getBytes(StandardCharsets.UTF_8),
                providedValue.getBytes(StandardCharsets.UTF_8))) {
            log.warn("revenuecat.webhook.auth mismatch");
            throw new InvalidWebhookSecretException();
        }
    }
}
