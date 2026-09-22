package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.privacy.LogMasker;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Sends auth-flow emails (password reset, verification) directly — these
 * MUST go out regardless of the per-user notification preferences. If
 * SMTP isn't configured (dev), logs the content with a clear DEV-MODE
 * marker so the developer can copy it from the logs and finish the flow.
 *
 * <p>All copy comes from the message bundle in the RECIPIENT's locale (their
 * stored {@code User.locale}), not the request's — the email is the user's,
 * so it's written in their language. Sent as branded HTML with a plain-text
 * fallback. Distinct from {@code EmailDispatcher} which is preference-driven.
 */
@Slf4j
@Component
public class AuthEmailSender {

    private final Optional<JavaMailSender> mailSender;
    private final LocalizedMessageService messageService;
    private final String from;
    private final boolean smtpConfigured;
    private final boolean devCodeLogEnabled;

    public AuthEmailSender(Optional<JavaMailSender> mailSender,
                           LocalizedMessageService messageService,
                           @Value("${economizaai.notifications.email.from:noreply@economizaai.app}") String from,
                           @Value("${spring.mail.username:}") String smtpUsername,
                           @Value("${economizaai.auth.dev-code-log-enabled:true}") boolean devCodeLogEnabled) {
        this.mailSender = mailSender;
        this.messageService = messageService;
        this.from = from;
        this.smtpConfigured = mailSender.isPresent() && smtpUsername != null && !smtpUsername.isBlank();
        this.devCodeLogEnabled = devCodeLogEnabled;
    }

    public void sendPasswordResetCode(String email, Locale locale, String code, int ttlMinutes) {
        var text = messageService.translate("auth.email.reset.text", locale, ttlMinutes, code);
        var html = codeEmailHtml(locale,
                messageService.translate("auth.email.reset.heading", locale),
                messageService.translate("auth.email.reset.intro", locale),
                code,
                messageService.translate("auth.email.reset.validity", locale, ttlMinutes),
                messageService.translate("auth.email.reset.disclaimer", locale));
        send(email, messageService.translate("auth.email.reset.subject", locale), text, html, "password-reset");
    }

    public void sendEmailVerification(String email, Locale locale, String code, int ttlHours) {
        var text = messageService.translate("auth.email.verify.text", locale, ttlHours, code);
        var html = codeEmailHtml(locale,
                messageService.translate("auth.email.verify.heading", locale),
                messageService.translate("auth.email.verify.intro", locale),
                code,
                messageService.translate("auth.email.verify.validity", locale, ttlHours),
                messageService.translate("auth.email.verify.disclaimer", locale));
        send(email, messageService.translate("auth.email.verify.subject", locale), text, html, "email-verification");
    }

    private void send(String to, String subject, String text, String html, String purpose) {
        if (!smtpConfigured) {
            if (devCodeLogEnabled) {
                // DEV-MODE: SMTP creds not wired. Log the would-be email so the
                // developer can copy the code/link and continue the flow. Documented
                // in DEV_NOTES.md as a "wire SMTP before prod" item.
                log.warn("[DEV-MODE] {} email NOT sent to {} (SMTP not configured). body:\n{}",
                        purpose, LogMasker.email(to), text);
            } else {
                // Prod: a reset/verify code in the logs is an account-takeover
                // vector — record the misconfiguration, never the code.
                log.error("auth_email.not_sent purpose={} to={} reason=smtp_not_configured",
                        purpose, LogMasker.email(to));
            }
            return;
        }
        try {
            var sender = mailSender.get();
            MimeMessage message = sender.createMimeMessage();
            // multipart so we can carry BOTH the HTML and a plain-text fallback.
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);   // (plain, html) — clients pick what they can render
            sender.send(message);
            log.info("auth_email.sent purpose={} to={}", purpose, LogMasker.email(to));
        } catch (Exception ex) {
            log.warn("auth_email.failed purpose={} to={} {}: {}",
                    purpose, LogMasker.email(to), ex.getClass().getSimpleName(), ex.getMessage());
            // Don't propagate — the user shouldn't get an error if SMTP is
            // having a bad day. The token is already persisted; they can
            // request another reset link.
        }
    }

    // Branded HTML for a CODE email (big, copy-friendly code block). Inline styles
    // only — email clients strip <style>/external CSS. Table layout + max 600px is
    // the email-safe convention that renders consistently across Gmail/Outlook/Apple.
    private String codeEmailHtml(Locale locale, String heading, String intro, String code,
                                 String validity, String disclaimer) {
        var codeBlock = """
                <div style="margin:28px 0;text-align:center;">
                  <div style="display:inline-block;background:#f1f5f9;border:1px solid #e2e8f0;border-radius:12px;
                              padding:18px 32px;font-family:'Courier New',Courier,monospace;font-size:34px;
                              font-weight:700;letter-spacing:10px;color:#0f172a;">%s</div>
                </div>
                <p style="margin:0 0 8px;color:#64748b;font-size:13px;text-align:center;">%s</p>
                """.formatted(escape(code), escape(validity));
        return shell(locale, heading, intro, codeBlock, disclaimer);
    }

    // Shared outer shell: header band, white card, content, footer. One green brand
    // accent (#16a34a) to match the app, neutral slate text, centered 600px card.
    private String shell(Locale locale, String heading, String intro, String content, String footnote) {
        return """
                <!DOCTYPE html>
                <html lang="%s"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f1f5f9;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f5f9;padding:32px 12px;">
                    <tr><td align="center">
                      <table role="presentation" width="600" cellpadding="0" cellspacing="0"
                             style="max-width:600px;width:100%%;background:#ffffff;border-radius:16px;overflow:hidden;
                                    box-shadow:0 1px 3px rgba(0,0,0,0.08);font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
                        <tr><td style="background:#16a34a;padding:24px 32px;">
                          <span style="color:#ffffff;font-size:22px;font-weight:700;letter-spacing:-0.5px;">economizai</span>
                        </td></tr>
                        <tr><td style="padding:32px 32px 8px;">
                          <h1 style="margin:0 0 12px;color:#0f172a;font-size:20px;font-weight:700;">%s</h1>
                          <p style="margin:0;color:#334155;font-size:15px;line-height:1.6;">%s</p>
                          %s
                          <p style="margin:16px 0 0;color:#94a3b8;font-size:13px;line-height:1.6;">%s</p>
                        </td></tr>
                        <tr><td style="padding:24px 32px 32px;">
                          <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 16px;">
                          <p style="margin:0;color:#94a3b8;font-size:12px;line-height:1.5;">%s</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """.formatted(escape(languageTag(locale)), escape(heading), escape(intro), content,
                escape(footnote), messageService.translate("auth.email.footer", locale));
    }

    private static String languageTag(Locale locale) {
        return locale == null ? "pt-BR" : locale.toLanguageTag();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
