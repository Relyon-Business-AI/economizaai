package com.relyon.economizaai.service.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizaai.service.LocalizedMessageService;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthEmailSenderTest {

    private static final String FROM = "noreply@economiz.ai";
    private static final String RECIPIENT = "maria@example.com";
    private static final String RESET_CODE = "123456";
    private static final String VERIFY_CODE = "654321";
    private static final Locale PT = Locale.forLanguageTag("pt");

    // Real message service over the actual bundle, so content assertions verify localized copy.
    private LocalizedMessageService messageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    // A real (empty-session) MimeMessage the mocked sender hands back, so the helper
    // can populate it and we can read the rendered subject/recipients/body back.
    private JavaMailSender mailSenderReturningRealMime() {
        var mailSender = mock(JavaMailSender.class);
        var session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(session));
        return mailSender;
    }

    // The most robust way to read a built MimeMessage is to serialize it: writeTo
    // renders the full MIME payload (headers + every part), which we can assert on.
    // (getContent() on a not-yet-saved message can return an unreadable tree.) The
    // HTML body is quoted-printable-encoded in the output, so decode soft line
    // breaks before asserting on substrings like the code/link.
    private String bodyOf(MimeMessage msg) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        msg.writeTo(out);
        var raw = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        // undo quoted-printable soft breaks ("=\r\n") and =XX is left as-is; for our
        // assertions (ascii code digits, ascii url) the soft-break removal suffices.
        return raw.replace("=\r\n", "").replace("=\n", "");
    }

    @Test
    void sendsPasswordResetCodeWhenSmtpConfigured() throws Exception {
        var mailSender = mailSenderReturningRealMime();
        var sender = new AuthEmailSender(Optional.of(mailSender), messageService(), FROM, "smtp-user", true);

        sender.sendPasswordResetCode(RECIPIENT, PT, RESET_CODE, 60);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertArrayEquals(new jakarta.mail.Address[]{new jakarta.mail.internet.InternetAddress(RECIPIENT)},
                sent.getRecipients(MimeMessage.RecipientType.TO));
        assertEquals("Seu código de redefinição de senha — economizai", sent.getSubject());
        var body = bodyOf(sent);
        assertTrue(body.contains(RESET_CODE), "rendered email must contain the code");
        assertTrue(body.contains("economizai"), "rendered email must be branded");
    }

    @Test
    void resetEmail_localizedToRecipientLocale_en() throws Exception {
        var mailSender = mailSenderReturningRealMime();
        var sender = new AuthEmailSender(Optional.of(mailSender), messageService(), FROM, "smtp-user", true);

        sender.sendPasswordResetCode(RECIPIENT, Locale.ENGLISH, RESET_CODE, 60);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertEquals("Your password reset code — economizai", sent.getSubject());
        var body = bodyOf(sent);
        assertTrue(body.contains("Password reset"), "en heading");
        assertTrue(body.contains(RESET_CODE));
    }

    @Test
    void sendsEmailVerificationCodeWhenSmtpConfigured() throws Exception {
        var mailSender = mailSenderReturningRealMime();
        var sender = new AuthEmailSender(Optional.of(mailSender), messageService(), FROM, "smtp-user", true);

        sender.sendEmailVerification(RECIPIENT, PT, VERIFY_CODE, 24);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertEquals("Seu código de confirmação — economizai", sent.getSubject());
        var body = bodyOf(sent);
        assertTrue(body.contains(VERIFY_CODE), "rendered email must contain the code");
        assertTrue(body.contains("24 horas"), "rendered email must state the TTL");
    }

    @Test
    void doesNotSendWhenSmtpUsernameBlank() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), messageService(), FROM, "   ", true);

        sender.sendPasswordResetCode(RECIPIENT, PT, RESET_CODE, 60);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void doesNotSendWhenSmtpUsernameNull() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), messageService(), FROM, null, true);

        sender.sendEmailVerification(RECIPIENT, PT, VERIFY_CODE, 24);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void doesNotSendWhenMailSenderAbsentEvenWithUsername() {
        var sender = new AuthEmailSender(Optional.empty(), messageService(), FROM, "smtp-user", true);

        // No mailSender bean to send through; must be a silent no-op.
        assertDoesNotThrow(() -> sender.sendPasswordResetCode(RECIPIENT, PT, RESET_CODE, 60));
    }

    private ListAppender<ILoggingEvent> attachLogCapture() {
        var logger = (Logger) LoggerFactory.getLogger(AuthEmailSender.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void devCodeLogEnabled_logsCodeWhenSmtpNotConfigured() {
        var appender = attachLogCapture();
        var sender = new AuthEmailSender(Optional.empty(), messageService(), FROM, "", true);

        sender.sendPasswordResetCode(RECIPIENT, PT, RESET_CODE, 60);

        assertTrue(appender.list.stream()
                        .anyMatch(loggedEvent -> loggedEvent.getFormattedMessage().contains(RESET_CODE)),
                "dev fallback must log the code so the flow can be finished from the logs");
    }

    @Test
    void devCodeLogDisabled_neverLogsCodeWhenSmtpNotConfigured() {
        var appender = attachLogCapture();
        var sender = new AuthEmailSender(Optional.empty(), messageService(), FROM, "", false);

        sender.sendPasswordResetCode(RECIPIENT, PT, RESET_CODE, 60);
        sender.sendEmailVerification(RECIPIENT, PT, VERIFY_CODE, 24);

        assertTrue(appender.list.stream()
                        .noneMatch(loggedEvent -> loggedEvent.getFormattedMessage().contains(RESET_CODE)
                                || loggedEvent.getFormattedMessage().contains(VERIFY_CODE)),
                "prod must never log an auth code — it is an account-takeover vector");
    }

    @Test
    void swallowsExceptionFromMailSenderSoCallerNeverErrors() {
        var mailSender = mailSenderReturningRealMime();
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        var sender = new AuthEmailSender(Optional.of(mailSender), messageService(), FROM, "smtp-user", true);

        assertDoesNotThrow(() -> sender.sendPasswordResetCode(RECIPIENT, PT, RESET_CODE, 60));
        verify(mailSender).send(any(MimeMessage.class));
    }
}
