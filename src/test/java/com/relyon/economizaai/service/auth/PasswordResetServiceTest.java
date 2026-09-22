package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.dto.request.ForgotPasswordRequest;
import com.relyon.economizaai.dto.request.ResetPasswordRequest;
import com.relyon.economizaai.dto.request.VerifyResetCodeRequest;
import com.relyon.economizaai.exception.InvalidAuthTokenException;
import com.relyon.economizaai.model.PasswordResetToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.repository.PasswordResetTokenRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthEmailSender emailSender;

    @InjectMocks private PasswordResetService passwordResetService;

    private User registeredUser() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").password("old-hash").build();
    }

    private PasswordResetToken activeCode(User user, String code) {
        return PasswordResetToken.builder()
                .user(user).token(sha256(code))
                .expiresAt(LocalDateTime.now().plusMinutes(30)).build();
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void stubActiveCode(User user, PasswordResetToken token) {
        when(tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(token));
    }

    @Test
    void requestResetForUnknownEmailIsNoOpButStillSucceeds() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        passwordResetService.requestReset(new ForgotPasswordRequest("ghost@example.com"));

        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestResetForSocialOnlyAccountIsNoOp() {
        var socialUser = User.builder().id(UUID.randomUUID())
                .email("maria@example.com")
                .authProvider(AuthProvider.GOOGLE)
                .build();
        when(userRepository.findByEmail("maria@example.com")).thenReturn(Optional.of(socialUser));

        passwordResetService.requestReset(new ForgotPasswordRequest("maria@example.com"));

        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestResetForKnownEmailInvalidatesOldCodesPersistsCodeAndEmailsIt() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        passwordResetService.requestReset(new ForgotPasswordRequest(user.getEmail()));

        // old codes invalidated before a new one is issued
        verify(tokenRepository).consumeAllActiveForUser(eq(user), any(LocalDateTime.class));

        var tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        var savedToken = tokenCaptor.getValue();
        assertEquals(user, savedToken.getUser());
        assertTrue(savedToken.getExpiresAt().isAfter(LocalDateTime.now()));

        // a 6-digit code is emailed; only its hash is persisted
        var codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendPasswordResetCode(eq(user.getEmail()), any(java.util.Locale.class), codeCaptor.capture(), eq(60));
        var emailedCode = codeCaptor.getValue();
        assertTrue(emailedCode.matches("\\d{6}"), "emailed code must be exactly 6 digits");
        assertEquals(sha256(emailedCode), savedToken.getToken(), "stored token must be the code's hash");
    }

    @Test
    void verifyCodeWithValidCodeSucceedsWithoutConsuming() {
        var user = registeredUser();
        var code = activeCode(user, "123456");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, code);

        passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "123456"));

        // verify-only: nothing consumed, no password touched, no attempt burned
        verify(tokenRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertEquals(0, code.getAttempts());
    }

    @Test
    void verifyCodeWithWrongCodeThrowsAndBurnsAnAttempt() {
        var user = registeredUser();
        var code = activeCode(user, "123456");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, code);

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "000000")));

        assertEquals(1, code.getAttempts());
        verify(tokenRepository).save(code);
    }

    @Test
    void verifyCodeWithNoActiveCodeThrows() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user))
                .thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "000000")));
    }

    @Test
    void codeLocksAfterMaxFailedAttemptsEvenIfGuessedRightAfterwards() {
        var user = registeredUser();
        var code = activeCode(user, "123456");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, code);

        for (var attempt = 0; attempt < 5; attempt++) {
            assertThrows(InvalidAuthTokenException.class, () ->
                    passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "000000")));
        }
        assertEquals(5, code.getAttempts());

        // the CORRECT code is now rejected — the attacker exhausted the budget
        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "123456")));
        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(
                        new ResetPasswordRequest(user.getEmail(), "123456", "brand-new-password")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPasswordWithValidCodeUpdatesPasswordAndConsumesCode() {
        var user = registeredUser();
        var code = activeCode(user, "654321");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, code);
        when(passwordEncoder.encode("brand-new-password")).thenReturn("new-hash");

        passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "654321", "brand-new-password"));

        assertEquals("new-hash", user.getPassword());
        verify(userRepository).save(user);
        assertNotNull(code.getConsumedAt());
        verify(tokenRepository).save(code);
    }

    @Test
    void resetPasswordWithWrongCodeThrowsAndChangesNothing() {
        var user = registeredUser();
        var code = activeCode(user, "654321");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, code);

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "999999", "brand-new-password")));

        assertEquals(1, code.getAttempts());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithUnknownEmailThrows() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest("ghost@example.com", "123456", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithNoActiveCodeThrows() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user))
                .thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "111111", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithExpiredCodeThrows() {
        var user = registeredUser();
        var expired = PasswordResetToken.builder()
                .user(user).token("222222")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, expired);

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "222222", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }
}
