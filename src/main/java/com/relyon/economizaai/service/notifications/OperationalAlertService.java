package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.dto.response.AdminAlertsConfigResponse;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Single funnel for URGENT operational alerts to the owner/admins (Infosimples saldo,
 * paid-API outages, portal regressions…). Fans out to two channels:
 * <ul>
 *   <li><b>E-mail</b> — ALWAYS (full detail), via {@link ContactService#notifyAdmin}.</li>
 *   <li><b>WhatsApp</b> — the headline, to each {@code ADMIN_PHONE_NUMBERS} number, when the
 *       Twilio WhatsApp sender + approved template are configured and the switch is on. Proactive
 *       WhatsApp needs an approved template (ContentSid) — freeform text is rejected by Meta.</li>
 * </ul>
 *
 * <p>Alerting must never break the caller: every channel is wrapped so a dispatch failure is
 * logged and swallowed. WhatsApp degrades to e-mail-only when unconfigured.
 */
@Slf4j
@Service
public class OperationalAlertService {

    private final ContactService contactService;
    private final TwilioMessageClient twilioClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> adminPhoneNumbers;
    private final String whatsAppTemplateSid;
    private final boolean whatsAppEnabled;

    public OperationalAlertService(
            ContactService contactService,
            TwilioMessageClient twilioClient,
            @Value("${economizaai.notifications.admin-phone-numbers:}") String adminPhoneNumbersCsv,
            @Value("${economizaai.notifications.twilio.admin-template-sid:}") String whatsAppTemplateSid,
            @Value("${economizaai.notifications.admin-whatsapp-enabled:true}") boolean whatsAppEnabled) {
        this.contactService = contactService;
        this.twilioClient = twilioClient;
        this.adminPhoneNumbers = parsePhones(adminPhoneNumbersCsv);
        this.whatsAppTemplateSid = whatsAppTemplateSid;
        this.whatsAppEnabled = whatsAppEnabled;
        log.info("ops_alert.config whatsappEnabled={} admins={} template={}",
                whatsAppEnabled, adminPhoneNumbers.size(),
                isBlank(whatsAppTemplateSid) ? "unset" : "set");
    }

    /**
     * Fires an urgent admin alert. {@code subject} is the headline (also the WhatsApp body),
     * {@code body} the full detail (e-mail only). Never throws.
     */
    public void alertAdmin(String subject, String body) {
        sendEmail(subject, body);
        sendWhatsApp(subject);
    }

    /**
     * Current state of the alert channels for the admin screen. Queries Twilio live for the
     * template's Meta-approval status (so the admin knows if the WhatsApp channel is ready).
     */
    public AdminAlertsConfigResponse config() {
        var templateConfigured = !isBlank(whatsAppTemplateSid);
        var templateStatus = templateConfigured
                ? twilioClient.fetchWhatsAppTemplateStatus(whatsAppTemplateSid)
                : "unset";
        return new AdminAlertsConfigResponse(
                true,
                whatsAppEnabled,
                adminPhoneNumbers.size(),
                templateConfigured,
                twilioClient.isConfigured(true),
                templateStatus);
    }

    private void sendEmail(String subject, String body) {
        try {
            contactService.notifyAdmin(subject, body);
        } catch (RuntimeException ex) {
            log.warn("ops_alert.email_failed subject='{}' reason={}", subject, ex.getClass().getSimpleName());
        }
    }

    private void sendWhatsApp(String headline) {
        if (!whatsAppEnabled || isBlank(whatsAppTemplateSid) || adminPhoneNumbers.isEmpty()
                || !twilioClient.isConfigured(true)) {
            return;
        }
        var variablesJson = templateVariables(headline);
        if (variablesJson == null) return;
        for (var phone : adminPhoneNumbers) {
            try {
                twilioClient.sendWhatsAppTemplate(phone, whatsAppTemplateSid, variablesJson);
                log.info("ops_alert.whatsapp_sent to={}", mask(phone));
            } catch (RuntimeException ex) {
                log.warn("ops_alert.whatsapp_failed to={} reason={}", mask(phone), ex.getClass().getSimpleName());
            }
        }
    }

    /** {@code {"1":"<headline>"}} with proper JSON escaping of the headline. */
    private String templateVariables(String headline) {
        try {
            return objectMapper.writeValueAsString(Map.of("1", headline));
        } catch (Exception ex) {
            log.warn("ops_alert.whatsapp_vars_failed reason={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static List<String> parsePhones(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(phone -> !phone.isEmpty())
                .toList();
    }

    private static String mask(String phone) {
        return phone == null || phone.length() < 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
