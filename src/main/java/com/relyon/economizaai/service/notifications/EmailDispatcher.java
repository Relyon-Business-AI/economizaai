package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP-backed email channel. Active only when {@code economizaai.notifications.email.enabled=true}
 * AND a JavaMailSender bean exists (from spring-boot-starter-mail with
 * SMTP_HOST/PORT/USERNAME/PASSWORD env vars set). When inactive the
 * service degrades gracefully — the dispatcher list just won't contain
 * an email channel and EMAIL preferences fall back to PUSH or NONE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "economizaai.notifications.email.enabled", havingValue = "true", matchIfMissing = false)
public class EmailDispatcher implements NotificationDispatcher {

    private final JavaMailSender mailSender;

    @Value("${economizaai.notifications.email.from:noreply@economizaai.app}")
    private String from;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DispatchResult dispatch(NotificationPayload payload) {
        if (payload.user().getEmail() == null || payload.user().getEmail().isBlank()) {
            return DispatchResult.failed("user has no email");
        }
        try {
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(payload.user().getEmail());
            message.setSubject(payload.title());
            message.setText(payload.body());
            mailSender.send(message);
            log.info("notification.email.sent to={} type={} title='{}'",
                    LogMasker.email(payload.user().getEmail()), payload.type(), payload.title());
            return DispatchResult.ok();
        } catch (Exception ex) {
            log.warn("notification.email.failed to={} {}: {}",
                    LogMasker.email(payload.user().getEmail()), ex.getClass().getSimpleName(), ex.getMessage());
            return DispatchResult.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
