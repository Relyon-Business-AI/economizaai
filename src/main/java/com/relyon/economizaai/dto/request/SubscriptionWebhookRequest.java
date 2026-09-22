package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Provider-agnostic billing webhook payload. A real provider (Stripe / Mercado
 * Pago) maps its own webhook event onto this shape. ACTIVATE grants PRO, CANCEL
 * drops to FREE.
 */
public record SubscriptionWebhookRequest(
        @Schema(description = "Email of the subscribing user.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email String userEmail,
        @Schema(description = "ACTIVATE or CANCEL.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Action action,
        @Schema(description = "Payment provider name (e.g. stripe, mercadopago).")
        String provider,
        @Schema(description = "Provider's subscription identifier.")
        String providerRef,
        @Schema(description = "End of the currently-paid period (for ACTIVATE).")
        LocalDateTime currentPeriodEnd
) {
    public enum Action { ACTIVATE, CANCEL }
}
