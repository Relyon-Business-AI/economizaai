package com.relyon.economizaai.dto.response;

import java.time.LocalDateTime;

/**
 * {@code signupPromoGranted}/{@code signupPromoValidUntil} are only ever true/non-null
 * when THIS call just created the account and the signup promo ("até segunda ordem",
 * see {@code SubscriptionService#grantSignupPromoIfEnabled}) granted it PRO — the FE
 * uses them to show a one-time "you got N months free" banner right after registration.
 * Always false/null for login and token refresh.
 *
 * <p>{@code newAccount} is true only when THIS call created the account (e-mail
 * registration, or a social sign-in that created the user) — the FE fires its signup
 * analytics events off it. False on login, refresh and existing social accounts.
 */
public record AuthResponse(
        String token,
        String refreshToken,
        UserResponse user,
        boolean signupPromoGranted,
        LocalDateTime signupPromoValidUntil,
        boolean newAccount
) {
    /** Login / refresh shape: an existing account, never {@code newAccount}. */
    public AuthResponse(String token, String refreshToken, UserResponse user,
                        boolean signupPromoGranted, LocalDateTime signupPromoValidUntil) {
        this(token, refreshToken, user, signupPromoGranted, signupPromoValidUntil, false);
    }
}
