package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.exception.InvalidAuthTokenException;
import com.relyon.economizaai.model.EmailVerificationToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.EmailVerificationTokenRepository;
import com.relyon.economizaai.repository.UserRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private AuthEmailSender emailSender;

    @InjectMocks private EmailVerificationService emailVerificationService;

    private User unverifiedUser() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(false).build();
    }

    private EmailVerificationToken activeCode(User user, String code) {
        return EmailVerificationToken.builder()
                .user(user)
                .token(CodeHasher.sha256(code))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    private void stubActiveCode(User user, EmailVerificationToken token) {
        when(tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(token));
    }

    @Test
    void sendVerificationInvalidatesOldCodesPersistsHashAndEmailsCode() {
        var user = unverifiedUser();

        emailVerificationService.sendVerificationFor(user);

        verify(tokenRepository).consumeAllActiveForUser(eq(user), any(LocalDateTime.class));

        var tokenCaptor = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        var savedToken = tokenCaptor.getValue();
        assertEquals(user, savedToken.getUser());
        assertTrue(savedToken.getExpiresAt().isAfter(LocalDateTime.now()));

        // a 6-digit code is emailed; only its hash is persisted
        var codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmailVerification(eq(user.getEmail()), any(java.util.Locale.class), codeCaptor.capture(), eq(24));
        var emailedCode = codeCaptor.getValue();
        assertTrue(emailedCode.matches("\\d{6}"), "emailed code must be exactly 6 digits");
        assertEquals(CodeHasher.sha256(emailedCode), savedToken.getToken(),
                "stored token must be the code's hash");
    }

    @Test
    void sendVerificationForAlreadyVerifiedUserIsNoOp() {
        var user = User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(true).build();

        emailVerificationService.sendVerificationFor(user);

        verifyNoInteractions(tokenRepository);
        verifyNoInteractions(emailSender);
    }

    @Test
    void resendForUnverifiedUserDelegatesToSend() {
        var user = unverifiedUser();

        emailVerificationService.resend(user);

        verify(tokenRepository).save(any(EmailVerificationToken.class));
        verify(emailSender).sendEmailVerification(eq(user.getEmail()), any(java.util.Locale.class), anyString(), anyInt());
    }

    @Test
    void resendForVerifiedUserIsSilentNoOp() {
        var user = User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(true).build();

        emailVerificationService.resend(user);

        verifyNoInteractions(tokenRepository);
        verifyNoInteractions(emailSender);
    }

    @Test
    void verifyWithValidCodeMarksUserVerifiedAndConsumesCode() {
        var user = unverifiedUser();
        var token = activeCode(user, "123456");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, token);

        emailVerificationService.verify(user.getEmail(), "123456");

        assertTrue(user.isEmailVerified());
        assertNotNull(user.getEmailVerifiedAt());
        verify(userRepository).save(user);
        assertNotNull(token.getConsumedAt());
        verify(tokenRepository).save(token);
    }

    @Test
    void verifyIsIdempotentForAlreadyVerifiedUser() {
        var user = User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(true).build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        emailVerificationService.verify(user.getEmail(), "000000");

        verifyNoInteractions(tokenRepository);
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyWithUnknownEmailThrows() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class,
                () -> emailVerificationService.verify("ghost@example.com", "123456"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyWithNoActiveCodeThrows() {
        var user = unverifiedUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user))
                .thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class,
                () -> emailVerificationService.verify(user.getEmail(), "123456"));

        assertFalse(user.isEmailVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyWithWrongCodeThrowsAndBurnsAnAttempt() {
        var user = unverifiedUser();
        var token = activeCode(user, "123456");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, token);

        assertThrows(InvalidAuthTokenException.class,
                () -> emailVerificationService.verify(user.getEmail(), "000000"));

        assertEquals(1, token.getAttempts());
        verify(tokenRepository).save(token);
        assertFalse(user.isEmailVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void codeLocksAfterMaxFailedAttemptsEvenIfGuessedRightAfterwards() {
        var user = unverifiedUser();
        var token = activeCode(user, "123456");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, token);

        for (var attempt = 0; attempt < 5; attempt++) {
            assertThrows(InvalidAuthTokenException.class,
                    () -> emailVerificationService.verify(user.getEmail(), "000000"));
        }
        assertEquals(5, token.getAttempts());

        // the CORRECT code is now rejected — the attacker exhausted the budget
        assertThrows(InvalidAuthTokenException.class,
                () -> emailVerificationService.verify(user.getEmail(), "123456"));
        assertFalse(user.isEmailVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyWithExpiredCodeThrows() {
        var user = unverifiedUser();
        var expired = EmailVerificationToken.builder()
                .user(user)
                .token(CodeHasher.sha256("123456"))
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        stubActiveCode(user, expired);

        assertThrows(InvalidAuthTokenException.class,
                () -> emailVerificationService.verify(user.getEmail(), "123456"));

        assertFalse(user.isEmailVerified());
        verify(userRepository, never()).save(any());
    }
}
