package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.BetaSignupRequest;
import com.relyon.economizaai.dto.request.ContactRequest;
import com.relyon.economizaai.service.privacy.LogMasker;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Delivers a contact / feedback message to our support inbox. Mirrors
 * {@code AuthEmailSender}: sends via SMTP when configured, otherwise logs the
 * message with a DEV-MODE marker so it's never silently lost. The submitter's
 * address is set as Reply-To so we can answer them directly.
 */
@Slf4j
@Service
public class ContactService {

    private final Optional<JavaMailSender> mailSender;
    private final String from;
    private final String recipient;
    private final String betaRecipient;
    private final boolean smtpConfigured;

    public ContactService(Optional<JavaMailSender> mailSender,
                          @Value("${economizaai.notifications.email.from:noreply@economizaai.app}") String from,
                          @Value("${economizaai.contact.recipient:}") String recipient,
                          @Value("${economizaai.beta.recipient:}") String betaRecipient,
                          @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        this.from = from;
        this.recipient = recipient;
        // Beta signups can go to a dedicated inbox; falls back to the contact inbox
        // when not separately configured, so they're never dropped.
        this.betaRecipient = (betaRecipient == null || betaRecipient.isBlank()) ? recipient : betaRecipient;
        this.smtpConfigured = mailSender.isPresent() && smtpUsername != null && !smtpUsername.isBlank();
    }

    public void submit(ContactRequest request) {
        var name = singleLine(request.name());
        var subject = "[economizai contato] " + name;
        var phone = singleLine(request.phone());
        var body = "Nome: " + name
                + "\nE-mail: " + request.email()
                + (phone.isBlank() ? "" : "\nTelefone: " + phone)
                + "\n\nMensagem:\n" + request.message();
        deliver(subject, body, request.email(), recipient);
    }

    /**
     * Beta-tester lead capture. Distinct fixed subject so it lands in / is
     * filterable to the beta pile, and goes to the beta recipient (or the contact
     * inbox as fallback). No user-controlled subject — the tag is set here.
     */
    public void submitBetaSignup(BetaSignupRequest request) {
        var name = singleLine(request.name());
        var subject = "[economizai beta tester] " + name;
        var phone = singleLine(request.phone());
        var body = "Interessado(a) em ser beta tester:"
                + "\n\nNome: " + name
                + "\nE-mail: " + request.email()
                + (phone.isBlank() ? "" : "\nTelefone: " + phone);
        deliver(subject, body, request.email(), betaRecipient);
    }

    /**
     * Internal ops alert to the contact inbox — state-coverage events and the
     * like. Distinct subject prefix so it's filterable from user mail. Never
     * throws (deliver logs failures), so an alert can't break the caller's flow.
     */
    public void notifyAdmin(String subject, String body) {
        deliver("[economizai admin] " + subject, body, from, recipient);
    }

    private void deliver(String subject, String body, String replyTo, String recipient) {
        if (!smtpConfigured || recipient == null || recipient.isBlank()) {
            // No SMTP or no recipient configured — log the whole message so support
            // can still recover it from the logs (dev, or a misconfigured prod).
            log.warn("[DEV-MODE] contact message NOT emailed (smtp={}, recipient set={}). from={} subject='{}'\n{}",
                    smtpConfigured, recipient != null && !recipient.isBlank(), LogMasker.email(replyTo), subject, body);
            return;
        }
        try {
            var sender = mailSender.get();
            MimeMessage message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setReplyTo(replyTo);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            log.info("contact.sent from={} recipient={}", LogMasker.email(replyTo), LogMasker.email(recipient));
        } catch (Exception ex) {
            // Don't fail the request over an SMTP hiccup — log the full message so
            // it's recoverable, and let the user see a success.
            log.error("contact.failed from={} {}: {} — message was:\n{}",
                    LogMasker.email(replyTo), ex.getClass().getSimpleName(), ex.getMessage(), body);
        }
    }

    /** Strip CR/LF so a crafted name can't inject extra email headers into the subject. */
    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", " ").trim();
    }
}
