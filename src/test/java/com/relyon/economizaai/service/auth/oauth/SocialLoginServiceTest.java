package com.relyon.economizaai.service.auth.oauth;

import com.relyon.economizaai.dto.request.AppleLoginRequest;
import com.relyon.economizaai.dto.request.GoogleLoginRequest;
import com.relyon.economizaai.exception.InvalidOAuthTokenException;
import com.relyon.economizaai.legal.LegalDocuments;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.HouseholdService;
import com.relyon.economizaai.service.attribution.AttributionResolver;
import com.relyon.economizaai.service.auth.LoginActivityRecorder;
import com.relyon.economizaai.service.auth.RefreshTokenService;
import com.relyon.economizaai.service.analytics.meta.MetaConversionsService;
import com.relyon.economizaai.service.auth.SignupAlertService;
import com.relyon.economizaai.service.notifications.NotificationRuleService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    @Mock
    private GoogleTokenVerifier googleTokenVerifier;

    @Mock
    private AppleTokenVerifier appleTokenVerifier;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private HouseholdService householdService;

    @Mock
    private NotificationRuleService notificationRuleService;

    @Mock
    private LoginActivityRecorder loginActivityRecorder;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private SignupAlertService signupAlertService;

    @Mock
    private MetaConversionsService metaConversionsService;

    @Mock
    private AttributionResolver attributionResolver;

    @InjectMocks
    private SocialLoginService socialLoginService;

    private void stubTokenIssue() {
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");
        when(refreshTokenService.issue(any(User.class))).thenReturn("refresh-token");
    }

    @Test
    void loginWithGoogle_newUser_createsHouseholdAndDefaultsEmailVerifiedNoPassword() {
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", "maria@example.com", true, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("maria@example.com")).thenReturn(Optional.empty());
        when(householdService.createSoloHousehold())
                .thenReturn(Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var user = inv.<User>getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        stubTokenIssue();

        var response = socialLoginService.loginWithGoogle(new GoogleLoginRequest("id-token", null));

        assertEquals("jwt-token", response.token());
        assertEquals("refresh-token", response.refreshToken());

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(AuthProvider.GOOGLE, saved.getAuthProvider());
        assertEquals("sub-1", saved.getProviderSubject());
        assertEquals("Maria", saved.getName());
        assertEquals("maria@example.com", saved.getEmail());
        assertTrue(saved.isEmailVerified());
        assertNull(saved.getPassword());
        assertEquals(LegalDocuments.CURRENT_TERMS_VERSION, saved.getAcceptedTermsVersion());
        assertEquals(LegalDocuments.CURRENT_PRIVACY_VERSION, saved.getAcceptedPrivacyVersion());
        verify(householdService).createSoloHousehold();
        verify(notificationRuleService).ensureDefaults(saved);
        verify(subscriptionService).grantSignupPromoIfEnabled(saved);
        assertEquals(false, response.signupPromoGranted());
        assertNull(response.signupPromoValidUntil());
    }

    @Test
    void loginWithGoogle_newUser_reflectsSignupPromoGrantInResponse() {
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", "maria@example.com", true, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("maria@example.com")).thenReturn(Optional.empty());
        when(householdService.createSoloHousehold())
                .thenReturn(Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        var promoValidUntil = LocalDateTime.now().plusMonths(3);
        when(subscriptionService.grantSignupPromoIfEnabled(any(User.class))).thenReturn(promoValidUntil);
        stubTokenIssue();

        var response = socialLoginService.loginWithGoogle(new GoogleLoginRequest("id-token", null));

        assertEquals(true, response.signupPromoGranted());
        assertEquals(promoValidUntil, response.signupPromoValidUntil());
    }

    @Test
    void loginWithGoogle_existingUserBySubject_logsInWithoutCreating() {
        var existing = User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@example.com")
                .authProvider(AuthProvider.GOOGLE)
                .providerSubject("sub-1")
                .build();
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", "maria@example.com", true, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.of(existing));
        stubTokenIssue();

        var response = socialLoginService.loginWithGoogle(new GoogleLoginRequest("id-token", null));

        assertEquals("jwt-token", response.token());
        verify(userRepository, never()).save(any(User.class));
        verify(householdService, never()).createSoloHousehold();
        verify(notificationRuleService, never()).ensureDefaults(any(User.class));
        verify(subscriptionService, never()).grantSignupPromoIfEnabled(any(User.class));
    }

    @Test
    void loginWithGoogle_existingLocalUserByEmail_linksProvider() {
        var local = User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@example.com")
                .password("encoded")
                .authProvider(AuthProvider.LOCAL)
                .build();
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", "maria@example.com", true, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("maria@example.com")).thenReturn(Optional.of(local));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTokenIssue();

        var response = socialLoginService.loginWithGoogle(new GoogleLoginRequest("id-token", null));

        assertEquals("jwt-token", response.token());
        assertEquals(AuthProvider.GOOGLE, local.getAuthProvider());
        assertEquals("sub-1", local.getProviderSubject());
        verify(userRepository).save(local);
        verify(householdService, never()).createSoloHousehold();
    }

    @Test
    void loginWithGoogle_unverifiedEmailMatchingLocalAccount_rejectedAndNotLinked() {
        var local = User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@example.com")
                .password("encoded")
                .authProvider(AuthProvider.LOCAL)
                .build();
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", "maria@example.com", false, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("maria@example.com")).thenReturn(Optional.of(local));
        var request = new GoogleLoginRequest("id-token", null);

        assertThrows(InvalidOAuthTokenException.class,
                () -> socialLoginService.loginWithGoogle(request));

        assertEquals(AuthProvider.LOCAL, local.getAuthProvider());
        assertNull(local.getProviderSubject());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginWithApple_unverifiedEmailMatchingLocalAccount_rejectedAndNotLinked() {
        var local = User.builder()
                .id(UUID.randomUUID())
                .name("Joao")
                .email("joao@example.com")
                .password("encoded")
                .authProvider(AuthProvider.LOCAL)
                .build();
        when(appleTokenVerifier.verify("identity-token", "Joao"))
                .thenReturn(new AppleTokenVerifier.AppleClaims("apple-sub-1", "joao@example.com", false, "Joao"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.APPLE, "apple-sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("joao@example.com")).thenReturn(Optional.of(local));
        var request = new AppleLoginRequest("identity-token", "Joao", null);

        assertThrows(InvalidOAuthTokenException.class,
                () -> socialLoginService.loginWithApple(request));

        assertEquals(AuthProvider.LOCAL, local.getAuthProvider());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginWithGoogle_newUserWithNullEmail_rejected() {
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", null, true, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.empty());
        var request = new GoogleLoginRequest("id-token", null);

        assertThrows(InvalidOAuthTokenException.class,
                () -> socialLoginService.loginWithGoogle(request));

        verify(userRepository, never()).save(any(User.class));
        verify(householdService, never()).createSoloHousehold();
    }

    @Test
    void loginWithGoogle_blankSubject_rejected() {
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("", "maria@example.com", true, "Maria"));
        var request = new GoogleLoginRequest("id-token", null);

        assertThrows(InvalidOAuthTokenException.class,
                () -> socialLoginService.loginWithGoogle(request));
    }

    @Test
    void loginWithApple_newUser_usesForwardedNameAndCreatesUser() {
        when(appleTokenVerifier.verify("identity-token", "Joao"))
                .thenReturn(new AppleTokenVerifier.AppleClaims("apple-sub-1", "joao@example.com", true, "Joao"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.APPLE, "apple-sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("joao@example.com")).thenReturn(Optional.empty());
        when(householdService.createSoloHousehold())
                .thenReturn(Household.builder().id(UUID.randomUUID()).inviteCode("XYZ789").build());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var user = inv.<User>getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        stubTokenIssue();

        var response = socialLoginService.loginWithApple(new AppleLoginRequest("identity-token", "Joao", null));

        assertEquals("jwt-token", response.token());
        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(AuthProvider.APPLE, saved.getAuthProvider());
        assertEquals("apple-sub-1", saved.getProviderSubject());
        assertEquals("Joao", saved.getName());
        assertTrue(saved.isEmailVerified());
        assertNull(saved.getPassword());
        verify(notificationRuleService).ensureDefaults(saved);
        verify(subscriptionService).grantSignupPromoIfEnabled(saved);
    }

    @Test
    void loginWithApple_newUserWithoutName_fallsBackToDefault() {
        when(appleTokenVerifier.verify("identity-token", null))
                .thenReturn(new AppleTokenVerifier.AppleClaims("apple-sub-2", "joao2@example.com", true, null));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.APPLE, "apple-sub-2"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("joao2@example.com")).thenReturn(Optional.empty());
        when(householdService.createSoloHousehold())
                .thenReturn(Household.builder().id(UUID.randomUUID()).inviteCode("XYZ000").build());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTokenIssue();

        socialLoginService.loginWithApple(new AppleLoginRequest("identity-token", null, null));

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("Usuario", captor.getValue().getName());
    }

    @Test
    void loginWithGoogle_existingUser_recordsLoginPlatform() {
        var existing = User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@example.com")
                .authProvider(AuthProvider.GOOGLE)
                .providerSubject("sub-1")
                .build();
        when(googleTokenVerifier.verify("id-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleClaims("sub-1", "maria@example.com", true, "Maria"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.of(existing));
        stubTokenIssue();

        socialLoginService.loginWithGoogle(new GoogleLoginRequest("id-token", Platform.WEB));

        verify(loginActivityRecorder).recordLogin(existing, Platform.WEB);
    }

    @Test
    void loginWithApple_newUser_recordsRegistrationPlatform() {
        when(appleTokenVerifier.verify("identity-token", "Joao"))
                .thenReturn(new AppleTokenVerifier.AppleClaims("apple-sub-1", "joao@example.com", true, "Joao"));
        when(userRepository.findByAuthProviderAndProviderSubject(AuthProvider.APPLE, "apple-sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("joao@example.com")).thenReturn(Optional.empty());
        when(householdService.createSoloHousehold())
                .thenReturn(Household.builder().id(UUID.randomUUID()).inviteCode("XYZ789").build());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTokenIssue();

        socialLoginService.loginWithApple(new AppleLoginRequest("identity-token", "Joao", Platform.IOS));

        verify(loginActivityRecorder).recordRegistration(any(User.class), eq(Platform.IOS));
    }
}
