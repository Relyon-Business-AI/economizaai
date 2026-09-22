package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.exception.InvalidPhoneNumberException;
import com.relyon.economizaai.exception.InvalidPhoneVerificationException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.PhoneVerificationToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.PhoneVerificationTokenRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PhoneVerificationTokenRepository tokenRepository;
    @Mock private TwilioMessageClient twilioMessageClient;
    @Mock private PaidApiGuardService paidApiGuardService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private PhoneVerificationService service;

    @BeforeEach
    void setUp() {
        service = new PhoneVerificationService(userRepository, tokenRepository, passwordEncoder,
                twilioMessageClient, paidApiGuardService, true);
    }

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").build();
    }

    @Test
    void setPhone_storesUnverifiedPhonePersistsHashedOtpAndDevLogsWhenTwilioNotConfigured() {
        var user = user();
        when(twilioMessageClient.isConfigured(false)).thenReturn(false);

        service.setPhoneAndSendOtp(user, "+5551999999999");

        assertEquals("+5551999999999", user.getPhoneNumber());
        assertFalse(user.isPhoneVerified());
        verify(userRepository).save(user);

        var captor = ArgumentCaptor.forClass(PhoneVerificationToken.class);
        verify(tokenRepository).save(captor.capture());
        var token = captor.getValue();
        assertNotNull(token.getCodeHash());
        // hashed at rest, not the raw 6-digit code
        assertFalse(token.getCodeHash().matches("\\d{6}"));
        assertTrue(token.getExpiresAt().isAfter(LocalDateTime.now()));
        // dev-mode: never calls Twilio
        verify(twilioMessageClient, never()).sendSms(anyString(), anyString());
    }

    @Test
    void setPhone_sendsViaTwilioWhenConfiguredAndRecordsPaidCall() {
        var user = user();
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);

        service.setPhoneAndSendOtp(user, "+5551999999999");

        verify(twilioMessageClient).sendSms(eq("+5551999999999"), anyString());
        verify(paidApiGuardService).assertWithinDailyCap(user.getId(), PaidApiService.TWILIO_MESSAGE);
        verify(paidApiGuardService).recordSuccess(user.getId(), PaidApiService.TWILIO_MESSAGE, null, "twilio");
    }

    @Test
    void setPhone_smsQuotaExceeded_rejectsBeforePersistingAnything() {
        var user = user();
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);
        doThrow(new PaidApiQuotaExceededException(PaidApiService.TWILIO_MESSAGE.name()))
                .when(paidApiGuardService).assertWithinDailyCap(user.getId(), PaidApiService.TWILIO_MESSAGE);

        var thrown = assertThrows(PaidApiQuotaExceededException.class,
                () -> service.setPhoneAndSendOtp(user, "+5551999999999"));

        assertEquals("phone.otp.quota_exceeded", thrown.getMessageKey());
        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
        verify(twilioMessageClient, never()).sendSms(anyString(), anyString());
    }

    @Test
    void setPhone_quotaNotCheckedWhenTwilioNotConfigured() {
        var user = user();
        when(twilioMessageClient.isConfigured(false)).thenReturn(false);

        service.setPhoneAndSendOtp(user, "+5551999999999");

        verify(paidApiGuardService, never()).assertWithinDailyCap(any(), any());
    }

    @Test
    void setPhone_rejectsNonE164() {
        var user = user();

        assertThrows(InvalidPhoneNumberException.class,
                () -> service.setPhoneAndSendOtp(user, "5551999999999"));
        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void setPhone_rejectsNull() {
        var user = user();
        assertThrows(InvalidPhoneNumberException.class,
                () -> service.setPhoneAndSendOtp(user, null));
    }

    @Test
    void verify_withCorrectCodeMarksVerifiedAndConsumesToken() {
        var user = user();
        user.setPhoneNumber("+5551999999999");
        var token = PhoneVerificationToken.builder()
                .user(user)
                .phoneNumber("+5551999999999")
                .codeHash(passwordEncoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.of(token));

        service.verify(user, "123456");

        assertTrue(user.isPhoneVerified());
        verify(userRepository).save(user);
        assertNotNull(token.getConsumedAt());
        verify(tokenRepository).save(token);
    }

    @Test
    void verify_withWrongCodeThrowsAndChangesNothing() {
        var user = user();
        var token = PhoneVerificationToken.builder()
                .user(user)
                .phoneNumber("+5551999999999")
                .codeHash(passwordEncoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.of(token));

        assertThrows(InvalidPhoneVerificationException.class, () -> service.verify(user, "000000"));

        assertFalse(user.isPhoneVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verify_withExpiredCodeThrows() {
        var user = user();
        var token = PhoneVerificationToken.builder()
                .user(user)
                .phoneNumber("+5551999999999")
                .codeHash(passwordEncoder.encode("123456"))
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenRepository.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.of(token));

        assertThrows(InvalidPhoneVerificationException.class, () -> service.verify(user, "123456"));

        assertFalse(user.isPhoneVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verify_withNoPendingTokenThrows() {
        var user = user();
        when(tokenRepository.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.empty());

        assertThrows(InvalidPhoneVerificationException.class, () -> service.verify(user, "123456"));
    }
}
