package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationNotice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Meta-approved Twilio Content template of each WhatsApp-capable notice. A message we
 * start (outside the 24h window the user opens by writing to us) is only accepted as a
 * template, so a notice without a configured template SID has no WhatsApp leg at all.
 * Every template takes a single variable, {@code {{1}}}: the notice in one line.
 */
@Component
public class WhatsAppTemplateRegistry {

    private final Map<NotificationNotice, String> templateSidByNotice = new EnumMap<>(NotificationNotice.class);

    public WhatsAppTemplateRegistry(
            @Value("${economizaai.notifications.twilio.price-alert-template-sid:}") String priceAlertTemplateSid,
            @Value("${economizaai.notifications.twilio.deals-digest-template-sid:}") String dealsDigestTemplateSid,
            @Value("${economizaai.notifications.twilio.report-template-sid:}") String weeklyReportTemplateSid) {
        register(NotificationNotice.PRICE_ALERTS, priceAlertTemplateSid);
        register(NotificationNotice.DEALS_DIGEST, dealsDigestTemplateSid);
        register(NotificationNotice.WEEKLY_REPORT, weeklyReportTemplateSid);
    }

    public Optional<String> templateSidFor(NotificationNotice notice) {
        return Optional.ofNullable(templateSidByNotice.get(notice));
    }

    private void register(NotificationNotice notice, String templateSid) {
        if (templateSid != null && !templateSid.isBlank()) templateSidByNotice.put(notice, templateSid.trim());
    }
}
