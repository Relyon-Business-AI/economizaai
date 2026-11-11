package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.dto.request.ConfirmEmailChangeRequest;
import com.relyon.economizaai.dto.request.RequestEmailChangeRequest;
import com.relyon.economizaai.exception.EmailChangeConflictException;
import com.relyon.economizaai.exception.InvalidCurrentPasswordException;
import com.relyon.economizaai.exception.InvalidEmailChangeCodeException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailChangeServiceTest {

    private static final String CURRENT_EMAIL = "maria@economizaai.app";
    private static final String NEW_EMAIL = "maria.nova@economizaai.app";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthEmailSender emailSender;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private EmailChangeService emailChangeService;

    private User storedUser;
    private User principal;

    @BeforeEach
    void setUp() {
        var userId = UUID.randomUUID();
        storedUser = User.builder().id(userId).email(CURRENT_EMAIL).password("hash").build();
        // The request principal is a separate, detached snapshot of the same account.
        principal = User.builder().id(userId).email(CURRENT_EMAIL).password("hash").build();
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(storedUser));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String requestChangeAndCaptureCode() {
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);
        emailChangeService.requestChange(principal, new RequestEmailChangeRequest(NEW_EMAIL, "password123"));
        var code = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmailChangeCode(eq(NEW_EMAIL), any(), code.capture(), eq(30));
        return code.getValue();
    }

    @Test
    void requestStoresPendingEmailAndHashedCodeWithoutTouchingCurrentEmail() {
        var code = requestChangeAndCaptureCode();

        assertThat(code).matches("\\d{6}");
        assertThat(storedUser.getEmail()).isEqualTo(CURRENT_EMAIL);
        assertThat(storedUser.getPendingEmail()).isEqualTo(NEW_EMAIL);
        assertThat(storedUser.getEmailChangeCodeHash()).isEqualTo(CodeHasher.sha256(code)).isNotEqualTo(code);
        assertThat(storedUser.getEmailChangeExpiresAt())
                .isBetween(LocalDateTime.now().plusMinutes(29), LocalDateTime.now().plusMinutes(31));
        assertThat(storedUser.getEmailChangeAttempts()).isZero();
    }

    @Test
    void requestNormalizesTheNewEmail() {
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        emailChangeService.requestChange(principal,
                new RequestEmailChangeRequest("  Maria.Nova@Economizaai.APP ", "password123"));

        assertThat(storedUser.getPendingEmail()).isEqualTo(NEW_EMAIL);
        verify(emailSender).sendEmailChangeCode(eq(NEW_EMAIL), any(), anyString(), anyInt());
    }

    @Test
    void requestOnSocialOnlyAccountIsRefused() {
        storedUser.setPassword(null);
        storedUser.setAuthProvider(AuthProvider.GOOGLE);

        assertThatThrownBy(() -> emailChangeService.requestChange(principal,
                new RequestEmailChangeRequest(NEW_EMAIL, "whatever")))
                .isInstanceOf(EmailChangeConflictException.class)
                .extracting("messageKey").isEqualTo("user.email-change.password-required");
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestWithWrongPasswordIsRefused() {
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> emailChangeService.requestChange(principal,
                new RequestEmailChangeRequest(NEW_EMAIL, "wrong")))
                .isInstanceOf(InvalidCurrentPasswordException.class);
        assertThat(storedUser.getPendingEmail()).isNull();
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestForAddressAlreadyInUseIsRefused() {
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);
        when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> emailChangeService.requestChange(principal,
                new RequestEmailChangeRequest(NEW_EMAIL, "password123")))
                .isInstanceOf(EmailChangeConflictException.class)
                .extracting("messageKey").isEqualTo("user.email-change.in-use");
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestForTheCurrentAddressIsRefused() {
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        assertThatThrownBy(() -> emailChangeService.requestChange(principal,
                new RequestEmailChangeRequest("MARIA@economizaai.app", "password123")))
                .isInstanceOf(EmailChangeConflictException.class)
                .extracting("messageKey").isEqualTo("user.email-change.same-email");
    }

    @Test
    void confirmSwitchesEmailMarksVerifiedRevokesOtherSessionsAndNotifiesOldAddress() {
        var code = requestChangeAndCaptureCode();

        var response = emailChangeService.confirmChange(principal, new ConfirmEmailChangeRequest(code, "device-token"));

        assertThat(response.email()).isEqualTo(NEW_EMAIL);
        assertThat(response.emailVerified()).isTrue();
        assertThat(response.pendingEmail()).isNull();
        assertThat(storedUser.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(storedUser.getEmailVerifiedAt()).isNotNull();
        assertThat(storedUser.getPendingEmail()).isNull();
        assertThat(storedUser.getEmailChangeCodeHash()).isNull();
        verify(refreshTokenService).revokeAllForUserExcept(storedUser, "device-token");
        verify(emailSender).sendEmailChangedNotice(eq(CURRENT_EMAIL), any(), eq("m***@economizaai.app"));
    }

    @Test
    void confirmWithWrongCodeCountsTheAttemptAndKeepsTheCurrentEmail() {
        var code = requestChangeAndCaptureCode();
        var wrongCode = "000000".equals(code) ? "111111" : "000000";

        assertThatThrownBy(() -> emailChangeService.confirmChange(principal,
                new ConfirmEmailChangeRequest(wrongCode, null)))
                .isInstanceOf(InvalidEmailChangeCodeException.class);

        assertThat(storedUser.getEmailChangeAttempts()).isEqualTo(1);
        assertThat(storedUser.getEmail()).isEqualTo(CURRENT_EMAIL);
        verify(refreshTokenService, never()).revokeAllForUserExcept(any(), any());
    }

    @Test
    void codeLocksAfterFiveWrongGuessesEvenIfGuessedRightAfterwards() {
        var code = requestChangeAndCaptureCode();
        var wrongCode = "000000".equals(code) ? "111111" : "000000";

        for (var attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> emailChangeService.confirmChange(principal,
                    new ConfirmEmailChangeRequest(wrongCode, null)))
                    .isInstanceOf(InvalidEmailChangeCodeException.class);
        }

        assertThatThrownBy(() -> emailChangeService.confirmChange(principal, new ConfirmEmailChangeRequest(code, null)))
                .isInstanceOf(InvalidEmailChangeCodeException.class);
        assertThat(storedUser.getEmail()).isEqualTo(CURRENT_EMAIL);
    }

    @Test
    void confirmWithExpiredCodeIsRefused() {
        var code = requestChangeAndCaptureCode();
        storedUser.setEmailChangeExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> emailChangeService.confirmChange(principal, new ConfirmEmailChangeRequest(code, null)))
                .isInstanceOf(InvalidEmailChangeCodeException.class);
        assertThat(storedUser.getEmail()).isEqualTo(CURRENT_EMAIL);
    }

    @Test
    void confirmWithoutPendingRequestIsRefused() {
        assertThatThrownBy(() -> emailChangeService.confirmChange(principal,
                new ConfirmEmailChangeRequest("123456", null)))
                .isInstanceOf(InvalidEmailChangeCodeException.class);
    }

    @Test
    void confirmIsRefusedWhenAddressWasTakenWhileTheCodeWasPending() {
        var code = requestChangeAndCaptureCode();
        when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> emailChangeService.confirmChange(principal, new ConfirmEmailChangeRequest(code, null)))
                .isInstanceOf(EmailChangeConflictException.class);
        assertThat(storedUser.getEmail()).isEqualTo(CURRENT_EMAIL);
        assertThat(storedUser.getPendingEmail()).isNull();
    }

    @Test
    void cancelClearsThePendingRequest() {
        var code = requestChangeAndCaptureCode();

        emailChangeService.cancelChange(principal);

        assertThat(storedUser.getPendingEmail()).isNull();
        assertThatThrownBy(() -> emailChangeService.confirmChange(principal, new ConfirmEmailChangeRequest(code, null)))
                .isInstanceOf(InvalidEmailChangeCodeException.class);
    }
}
