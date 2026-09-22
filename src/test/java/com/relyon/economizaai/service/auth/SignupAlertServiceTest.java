package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.service.ContactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SignupAlertServiceTest {

    private static final Executor INLINE_EXECUTOR = Runnable::run;

    @Mock
    private ContactService contactService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private User newUser() {
        return User.builder()
                .name("Maria Silva")
                .email("maria@example.com")
                .locale("pt-BR")
                .registrationPlatform(Platform.ANDROID)
                .build();
    }

    private void bindRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void sendsAdminEmailWithUserAndRequestInfo() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("104.16.1.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("CF-IPCountry", "BR");
        bindRequest(request);
        var service = new SignupAlertService(contactService, INLINE_EXECUTOR, true);

        service.notifyNewAccount(newUser(), "e-mail/senha");

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(contactService).notifyAdmin(anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("Maria Silva")
                .contains("maria@example.com")
                .contains("e-mail/senha")
                .contains("ANDROID")
                .contains("203.0.113.7")
                .contains("BR");
    }

    @Test
    void disabledFlagSendsNothing() {
        var service = new SignupAlertService(contactService, INLINE_EXECUTOR, false);

        service.notifyNewAccount(newUser(), "e-mail/senha");

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void survivesMissingRequestContext() {
        var service = new SignupAlertService(contactService, INLINE_EXECUTOR, true);

        service.notifyNewAccount(newUser(), "social login GOOGLE");

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(contactService).notifyAdmin(anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("(indisponível)").contains("social login GOOGLE");
    }

    @Test
    void rejectedPoolNeverFailsRegistration() {
        Executor rejectingExecutor = task -> { throw new RejectedExecutionException("pool full"); };
        var service = new SignupAlertService(contactService, rejectingExecutor, true);

        assertThatCode(() -> service.notifyNewAccount(newUser(), "e-mail/senha")).doesNotThrowAnyException();
        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }
}
