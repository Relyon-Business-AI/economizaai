package com.relyon.economizaai.service.report;

import com.relyon.economizaai.exception.ReportEmailUnavailableException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.ReceiptExportService.ExportFile;
import com.relyon.economizaai.service.privacy.LogMasker;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * E-mails a generated report to the requesting user — always and only to the
 * account's own registered address (never a caller-supplied recipient, so the
 * endpoint can't be used to spam third parties). Mirrors ContactService's
 * SMTP wiring; when SMTP isn't configured the request fails with a localized
 * 503 instead of silently dropping a file the user explicitly asked for.
 */
@Slf4j
@Service
public class ReportEmailService {

    private final Optional<JavaMailSender> mailSender;
    private final String from;
    private final boolean smtpConfigured;
    private final LocalizedMessageService localizedMessageService;

    public ReportEmailService(Optional<JavaMailSender> mailSender,
                              @Value("${economizaai.notifications.email.from:noreply@economizaai.app}") String from,
                              @Value("${spring.mail.username:}") String smtpUsername,
                              LocalizedMessageService localizedMessageService) {
        this.mailSender = mailSender;
        this.from = from;
        this.smtpConfigured = mailSender.isPresent() && smtpUsername != null && !smtpUsername.isBlank();
        this.localizedMessageService = localizedMessageService;
    }

    public void sendToOwnEmail(User user, ExportFile file, String filename) {
        if (!smtpConfigured) {
            log.warn("report.email.unavailable user={} (SMTP not configured)", LogMasker.email(user.getEmail()));
            throw new ReportEmailUnavailableException();
        }
        try {
            var sender = mailSender.get();
            MimeMessage message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject(localizedMessageService.translate("report.email.subject"));
            helper.setText(localizedMessageService.translate("report.email.body"), false);
            helper.addAttachment(filename, new ByteArrayResource(file.content()), file.mediaType());
            sender.send(message);
            log.info("report.email.sent user={} file={} bytes={}",
                    LogMasker.email(user.getEmail()), filename, file.content().length);
        } catch (Exception ex) {
            log.error("report.email.failed user={} reason={}", LogMasker.email(user.getEmail()), ex.getMessage());
            throw new ReportEmailUnavailableException();
        }
    }
}
