package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailDispatcherTest {

    private static final String FROM_ADDRESS = "noreply@economiz.ai";

    @Mock private JavaMailSender mailSender;

    @InjectMocks private EmailDispatcher emailDispatcher;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailDispatcher, "from", FROM_ADDRESS);
    }

    private NotificationPayload payload(String email) {
        var user = User.builder().id(UUID.randomUUID()).email(email).build();
        return new NotificationPayload(user, NotificationType.PROMO_PERSONAL, "Subject", "Body text", Map.of());
    }

    @Test
    void channelIsEmail() {
        assertEquals(NotificationChannel.EMAIL, emailDispatcher.channel());
    }

    @Test
    void dispatchSendsMessageAndReportsSuccess() {
        var result = emailDispatcher.dispatch(payload("maria@example.com"));

        assertTrue(result.delivered());
        assertNull(result.failureReason());
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertEquals(FROM_ADDRESS, sent.getFrom());
        assertArrayEquals(new String[]{"maria@example.com"}, sent.getTo());
        assertEquals("Subject", sent.getSubject());
        assertEquals("Body text", sent.getText());
    }

    @Test
    void dispatchFailsWhenUserHasNoEmailWithoutTouchingMailSender() {
        var result = emailDispatcher.dispatch(payload(null));

        assertFalse(result.delivered());
        assertEquals("user has no email", result.failureReason());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void dispatchFailsWhenUserEmailIsBlankWithoutTouchingMailSender() {
        var result = emailDispatcher.dispatch(payload("   "));

        assertFalse(result.delivered());
        assertEquals("user has no email", result.failureReason());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void dispatchReportsFailureWhenMailSenderThrows() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        var result = emailDispatcher.dispatch(payload("maria@example.com"));

        assertFalse(result.delivered());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().startsWith("MailSendException"));
        assertTrue(result.failureReason().contains("smtp down"));
    }
}
