package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SubscriptionWebhookRequest;
import com.relyon.economizaai.exception.InvalidWebhookSecretException;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Provider-agnostic billing webhook — the integration seam for a real payment
 * provider (Stripe / Mercado Pago). The provider maps its own webhook event to
 * {@link SubscriptionWebhookRequest} and posts here.
 *
 * <p>Public route (permitAll in SecurityConfig), authenticated instead by a
 * shared secret in the {@code X-Webhook-Secret} header, checked against
 * {@code economizaai.billing.webhook-secret} with a constant-time comparison.
 * When no secret is configured the webhook is <strong>disabled</strong> —
 * every call is rejected (fail-closed). Dev grants PRO via the admin
 * set-tier endpoint instead.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Provider-agnostic billing webhook (subscription lifecycle)")
public class SubscriptionWebhookController {

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;

    @Value("${economizaai.billing.webhook-secret:}")
    private String webhookSecret;

    @PostMapping("/subscription")
    public ResponseEntity<Void> subscription(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String providedSecret,
            @Valid @RequestBody SubscriptionWebhookRequest request) {
        verifySecret(providedSecret);
        var userOpt = userRepository.findByEmail(request.userEmail());
        if (userOpt.isEmpty()) {
            // Unknown user → no-op 200 so the provider doesn't retry, and we don't
            // leak whether the email maps to an account.
            log.warn("webhook.subscription unknown_user action={} user={} provider={}",
                    request.action(), LogMasker.email(request.userEmail()), request.provider());
            return ResponseEntity.ok().build();
        }
        var user = userOpt.get();
        switch (request.action()) {
            case ACTIVATE -> subscriptionService.activatePro(
                    user, request.provider(), request.providerRef(), request.currentPeriodEnd());
            case CANCEL -> subscriptionService.cancel(user);
        }
        log.info("webhook.subscription action={} user={} provider={}",
                request.action(), LogMasker.email(request.userEmail()), request.provider());
        return ResponseEntity.ok().build();
    }

    private void verifySecret(String providedSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            // Fail closed: no secret configured → webhook disabled, reject everything.
            log.warn("webhook.secret.not_configured rejecting");
            throw new InvalidWebhookSecretException();
        }
        var provided = providedSecret != null ? providedSecret : "";
        if (!MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("webhook.secret.mismatch");
            throw new InvalidWebhookSecretException();
        }
    }
}
