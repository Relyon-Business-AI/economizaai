package com.relyon.economizai.service.auth.oauth;

import com.relyon.economizai.dto.request.AppleLoginRequest;
import com.relyon.economizai.dto.request.AttributionInfo;
import com.relyon.economizai.dto.request.GoogleLoginRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.InvalidOAuthTokenException;
import com.relyon.economizai.legal.LegalDocuments;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.model.enums.Platform;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.HouseholdService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.analytics.meta.MetaConversionsService;
import com.relyon.economizai.service.attribution.AttributionResolver;
import com.relyon.economizai.service.auth.LoginActivityRecorder;
import com.relyon.economizai.service.auth.RefreshTokenService;
import com.relyon.economizai.service.auth.SignupAlertService;
import com.relyon.economizai.service.notifications.NotificationRuleService;
import com.relyon.economizai.service.privacy.LogMasker;
import com.relyon.economizai.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Server-side social sign-in. The mobile app obtains a provider token natively
 * (Google ID token / Apple identity token); we verify it, resolve-or-create the
 * local account, and issue OUR JWT + refresh token — exactly the AuthResponse
 * shape the password login returns.
 *
 * <p>Account resolution, in order: (1) match by (provider, subject); (2) match
 * by email and link the provider onto that existing LOCAL account — only when
 * the provider asserts the email is verified (else reject, to prevent takeover);
 * (3) create a new solo-household user with no password, carrying the provider's
 * emailVerified claim (a missing email is rejected outright).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private static final String DEFAULT_NAME = "Usuario";

    private final GoogleTokenVerifier googleTokenVerifier;
    private final AppleTokenVerifier appleTokenVerifier;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final HouseholdService householdService;
    private final NotificationRuleService notificationRuleService;
    private final LoginActivityRecorder loginActivityRecorder;
    private final SubscriptionService subscriptionService;
    private final SignupAlertService signupAlertService;
    private final MetaConversionsService metaConversionsService;
    private final AttributionResolver attributionResolver;

    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest request) {
        var claims = googleTokenVerifier.verify(request.idToken());
        if (claims.subject() == null || claims.subject().isBlank()) {
            throw new InvalidOAuthTokenException();
        }
        var resolved = resolveOrCreateUser(AuthProvider.GOOGLE, claims.subject(), claims.email(),
                claims.emailVerified(), claims.name(), request.platform(), request.attribution());
        return issueAuth(resolved.user(), resolved.signupPromoValidUntil());
    }

    @Transactional
    public AuthResponse loginWithApple(AppleLoginRequest request) {
        var claims = appleTokenVerifier.verify(request.identityToken(), request.name());
        if (claims.subject() == null || claims.subject().isBlank()) {
            throw new InvalidOAuthTokenException();
        }
        var resolved = resolveOrCreateUser(AuthProvider.APPLE, claims.subject(), claims.email(),
                claims.emailVerified(), claims.name(), request.platform(), request.attribution());
        return issueAuth(resolved.user(), resolved.signupPromoValidUntil());
    }

    /** {@code signupPromoValidUntil} is non-null only when this call just created the account and granted it the promo. */
    private record ResolvedUser(User user, LocalDateTime signupPromoValidUntil) {}

    private ResolvedUser resolveOrCreateUser(AuthProvider provider, String subject, String email,
                                     boolean emailVerified, String name, Platform platform,
                                     AttributionInfo attribution) {
        var bySubject = userRepository.findByAuthProviderAndProviderSubject(provider, subject);
        if (bySubject.isPresent()) {
            var user = bySubject.get();
            log.info("social.login matched_by_subject provider={} user={}", provider, LogMasker.email(user.getEmail()));
            loginActivityRecorder.recordLogin(user, platform);
            return new ResolvedUser(user, null);
        }
        if (email != null && !email.isBlank()) {
            var byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                var user = linkProvider(byEmail.get(), provider, subject, emailVerified);
                loginActivityRecorder.recordLogin(user, platform);
                return new ResolvedUser(user, null);
            }
        }
        return createSocialUser(provider, subject, email, emailVerified, name, platform, attribution);
    }

    private User linkProvider(User user, AuthProvider provider, String subject, boolean emailVerified) {
        if (user.getAuthProvider() == AuthProvider.LOCAL) {
            // Only auto-link a provider onto an existing password account when the provider
            // asserts the email is verified — otherwise an unverified token could take over
            // the local account by claiming its email.
            if (!emailVerified) {
                log.warn("social.login link_rejected_unverified_email provider={} user={}",
                        provider, LogMasker.email(user.getEmail()));
                throw new InvalidOAuthTokenException();
            }
            user.setAuthProvider(provider);
            user.setProviderSubject(subject);
            user = userRepository.save(user);
            log.info("social.login linked_by_email provider={} user={}", provider, LogMasker.email(user.getEmail()));
        } else {
            log.info("social.login matched_by_email provider={} user={}", provider, LogMasker.email(user.getEmail()));
        }
        return user;
    }

    private ResolvedUser createSocialUser(AuthProvider provider, String subject, String email,
                                  boolean emailVerified, String name, Platform platform,
                                  AttributionInfo attribution) {
        if (email == null || email.isBlank()) {
            log.warn("social.login create_rejected_missing_email provider={}", provider);
            throw new InvalidOAuthTokenException();
        }
        var household = householdService.createSoloHousehold();
        var resolvedName = (name != null && !name.isBlank()) ? name : DEFAULT_NAME;
        var user = User.builder()
                .name(resolvedName)
                .email(email)
                .password(null)
                .locale(LocalizedMessageService.requestLocaleTag())
                .authProvider(provider)
                .providerSubject(subject)
                .household(household)
                .emailVerified(emailVerified)
                .emailVerifiedAt(emailVerified ? LocalDateTime.now() : null)
                .acceptedTermsVersion(LegalDocuments.CURRENT_TERMS_VERSION)
                .acceptedPrivacyVersion(LegalDocuments.CURRENT_PRIVACY_VERSION)
                .acceptedLegalAt(LocalDateTime.now())
                .build();
        attributionResolver.applyTo(user, attribution);
        var savedUser = userRepository.save(user);
        notificationRuleService.ensureDefaults(savedUser);
        var signupPromoValidUntil = subscriptionService.grantSignupPromoIfEnabled(savedUser);
        loginActivityRecorder.recordRegistration(savedUser, platform);
        signupAlertService.notifyNewAccount(savedUser, "social login " + provider);
        metaConversionsService.reportCompleteRegistration(savedUser, provider.name().toLowerCase());
        log.info("social.login created provider={} user={} household={}",
                provider, LogMasker.email(savedUser.getEmail()), household.getId());
        return new ResolvedUser(savedUser, signupPromoValidUntil);
    }

    private AuthResponse issueAuth(User user, LocalDateTime signupPromoValidUntil) {
        var token = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.issue(user);
        return new AuthResponse(token, refreshToken, UserResponse.from(user),
                signupPromoValidUntil != null, signupPromoValidUntil);
    }
}
