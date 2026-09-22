package com.relyon.economizaai.service.report;

import com.relyon.economizaai.exception.ReportEmailUnavailableException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.ReceiptExportService.ExportFile;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportEmailServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private LocalizedMessageService localizedMessageService;

    @BeforeEach
    void stubTranslations() {
        lenient().when(localizedMessageService.translate(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("alexandre+report@economizaai.app").build();
    }

    private ExportFile csvFile() {
        return new ExportFile("data".getBytes(), "text/csv", "csv");
    }

    @Test
    void send_withoutSmtpConfigured_throwsLocalized503() {
        var service = new ReportEmailService(Optional.empty(), "noreply@economizaai.app", "",
                localizedMessageService);

        assertThrows(ReportEmailUnavailableException.class,
                () -> service.sendToOwnEmail(user(), csvFile(), "relatorio.csv"));
    }

    @Test
    void send_withSmtp_deliversAttachmentToOwnEmailOnly() {
        var mimeMessage = mock(MimeMessage.class, Answers.RETURNS_DEEP_STUBS);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        var service = new ReportEmailService(Optional.of(mailSender), "noreply@economizaai.app",
                "smtp-user", localizedMessageService);

        service.sendToOwnEmail(user(), csvFile(), "relatorio.csv");

        verify(mailSender).send(mimeMessage);
    }

    @Test
    void send_smtpFailure_surfacesAsUnavailable() {
        var mimeMessage = mock(MimeMessage.class, Answers.RETURNS_DEEP_STUBS);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(mimeMessage);
        var service = new ReportEmailService(Optional.of(mailSender), "noreply@economizaai.app",
                "smtp-user", localizedMessageService);

        assertThrows(ReportEmailUnavailableException.class,
                () -> service.sendToOwnEmail(user(), csvFile(), "relatorio.csv"));
    }
}
