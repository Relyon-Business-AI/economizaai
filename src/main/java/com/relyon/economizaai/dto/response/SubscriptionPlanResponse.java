package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;

/**
 * The Premium plan as the FE should present it. {@code webCheckoutAvailable}
 * flips to true the moment the Mercado Pago credentials land in the env — the
 * FE shows the subscribe button vs an "em breve" state based on it, no app
 * release needed.
 */
public record SubscriptionPlanResponse(
        BigDecimal monthlyAmount,
        String currency,
        boolean webCheckoutAvailable) {
}
