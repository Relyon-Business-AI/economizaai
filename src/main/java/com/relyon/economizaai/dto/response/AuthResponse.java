package com.relyon.economizaai.dto.response;

import java.time.LocalDateTime;

/**
 * {@code signupPromoGranted}/{@code signupPromoValidUntil} are only ever true/non-null
 * when THIS call just created the account and the signup promo ("até segunda ordem",
 * see {@code SubscriptionService#grantSignupPromoIfEnabled}) granted it PRO — the FE
 * uses them to show a one-time "you got N months free" banner right after registration.
 * Always false/null for login and token refresh.
 */
public record AuthResponse(
        String token,
        String refreshToken,
        UserResponse user,
        boolean signupPromoGranted,
        LocalDateTime signupPromoValidUntil
) {}
