package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.BetaSignupRequest;
import com.relyon.economizaai.dto.request.ContactRequest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock private org.springframework.mail.javamail.JavaMailSender mailSender;

    private ContactService configuredService() {
        // beta recipient blank → falls back to the contact recipient
        return new ContactService(Optional.of(mailSender), "noreply@economiz.ai", "support@economiz.ai", "", "smtp-user");
    }

    @Test
    void submit_configured_sendsEmailToSupportWithReplyToSubmitter() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submit(new ContactRequest("John Doe", "john@test.com", null, "Adorei o app, parabéns!"));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertEquals("[economizai contato] John Doe", sent.getSubject());
        assertEquals("support@economiz.ai", sent.getAllRecipients()[0].toString());
        assertEquals("john@test.com", sent.getReplyTo()[0].toString());
        assertTrue(sent.getContent().toString().contains("Adorei o app"));
    }

    @Test
    void submit_withPhone_includesPhoneInBody() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submit(new ContactRequest("John Doe", "john@test.com", "+55 51 99999-0000", "oi"));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertTrue(captor.getValue().getContent().toString().contains("Telefone: +55 51 99999-0000"));
    }

    @Test
    void submit_withoutPhone_omitsPhoneLine() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submit(new ContactRequest("John Doe", "john@test.com", null, "oi"));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertFalse(captor.getValue().getContent().toString().contains("Telefone"));
    }

    @Test
    void submit_stripsNewlinesFromNameToBlockHeaderInjection() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submit(new ContactRequest("Evil\r\nBcc: victim@x.com", "a@b.com", null, "hi"));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertFalse(captor.getValue().getSubject().contains("\n"));
        assertFalse(captor.getValue().getSubject().contains("\r"));
    }

    @Test
    void submit_smtpNotConfigured_doesNotSend() {
        var service = new ContactService(Optional.of(mailSender), "noreply@economiz.ai", "support@economiz.ai", "", "");

        service.submit(new ContactRequest("John", "john@test.com", null, "oi"));

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void submit_noRecipient_doesNotSend() {
        var service = new ContactService(Optional.of(mailSender), "noreply@economiz.ai", "", "", "smtp-user");

        service.submit(new ContactRequest("John", "john@test.com", null, "oi"));

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void betaSignup_configured_sendsWithBetaSubjectAndReplyTo() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submitBetaSignup(new BetaSignupRequest("Jane Beta", "jane@test.com", null));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertEquals("[economizai beta tester] Jane Beta", sent.getSubject());
        // beta recipient blank → falls back to the contact recipient
        assertEquals("support@economiz.ai", sent.getAllRecipients()[0].toString());
        assertEquals("jane@test.com", sent.getReplyTo()[0].toString());
    }

    @Test
    void betaSignup_dedicatedRecipient_routesToBetaInbox() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = new ContactService(Optional.of(mailSender), "noreply@economiz.ai",
                "support@economiz.ai", "beta@economiz.ai", "smtp-user");

        service.submitBetaSignup(new BetaSignupRequest("Jane Beta", "jane@test.com", null));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("beta@economiz.ai", captor.getValue().getAllRecipients()[0].toString());
    }

    @Test
    void betaSignup_withPhone_includesPhoneInBody() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submitBetaSignup(new BetaSignupRequest("Jane Beta", "jane@test.com", "+55 51 99999-0000"));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertTrue(captor.getValue().getContent().toString().contains("Telefone: +55 51 99999-0000"));
    }

    @Test
    void betaSignup_withoutPhone_omitsPhoneLine() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submitBetaSignup(new BetaSignupRequest("Jane Beta", "jane@test.com", null));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertFalse(captor.getValue().getContent().toString().contains("Telefone"));
    }

    @Test
    void betaSignup_stripsNewlinesFromName() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        var service = configuredService();

        service.submitBetaSignup(new BetaSignupRequest("Evil\r\nBcc: victim@x.com", "a@b.com", null));

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertFalse(captor.getValue().getSubject().contains("\n"));
        assertFalse(captor.getValue().getSubject().contains("\r"));
    }
}
