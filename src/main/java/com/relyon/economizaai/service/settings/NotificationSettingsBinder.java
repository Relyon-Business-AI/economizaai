package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.service.notifications.DealFeedbackService;
import com.relyon.economizaai.service.notifications.NotificationRuleEngine;
import com.relyon.economizaai.service.notifications.QuietHoursPolicy;
import com.relyon.economizaai.service.notifications.WhatsAppChannelPolicy;
import com.relyon.economizaai.service.notifications.schedule.ReplenishmentService;
import com.relyon.economizaai.service.notifications.schedule.SpendingReportScheduler;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Notifications: quiet hours, deal feedback filter, reminders, channels. */
@Component
@RequiredArgsConstructor
public class NotificationSettingsBinder implements SettingBinder {

    private final QuietHoursPolicy quietHours;
    private final DealFeedbackService dealFeedback;
    private final ReplenishmentService replenishment;
    private final NotificationRuleEngine ruleEngine;
    private final ReceiptIngestionService receiptIngestion;
    private final SpendingReportScheduler spendingReports;
    private final WhatsAppChannelPolicy whatsAppChannel;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.toggle(RuntimeSetting.QUIET_HOURS_ENABLED, quietHours::isEnabled, quietHours::setEnabled);
        bindings.integer(RuntimeSetting.QUIET_HOURS_START, quietHours::getStartHour, quietHours::setStartHour);
        bindings.integer(RuntimeSetting.QUIET_HOURS_END, quietHours::getEndHour, quietHours::setEndHour);
        bindings.integer(RuntimeSetting.QUIET_HOURS_MAX_DEFERRED,
                quietHours::getMaxDeferredPerUser, quietHours::setMaxDeferredPerUser);

        bindings.text(RuntimeSetting.RELEVANCE_MODE,
                () -> dealFeedback.mode().name(), name -> dealFeedback.setMode(RelevanceMode.valueOf(name)));
        bindings.integer(RuntimeSetting.RELEVANCE_DISMISSED_DAYS, dealFeedback::dismissedDays, dealFeedback::setDismissedDays);
        bindings.integer(RuntimeSetting.RELEVANCE_MUTED_DAYS, dealFeedback::mutedDays, dealFeedback::setMutedDays);

        bindings.integer(RuntimeSetting.REPLENISHMENT_MAX_REMINDERS,
                replenishment::getMaxRemindersPerPurchase, replenishment::setMaxRemindersPerPurchase);
        bindings.integer(RuntimeSetting.REPLENISHMENT_SPACING_DAYS,
                replenishment::getReminderSpacingDays, replenishment::setReminderSpacingDays);
        bindings.integer(RuntimeSetting.PRICE_ALERT_COOLDOWN_HOURS, ruleEngine::getCooldownHours, ruleEngine::setCooldownHours);
        bindings.integer(RuntimeSetting.RECEIPT_READY_THRESHOLD_MINUTES,
                receiptIngestion::getReadyThresholdMinutes, receiptIngestion::setReadyThresholdMinutes);

        bindings.toggle(RuntimeSetting.SPENDING_REPORTS_ENABLED, spendingReports::isEnabled, spendingReports::setEnabled);
        bindings.text(RuntimeSetting.WHATSAPP_AUDIENCE,
                () -> whatsAppChannel.getAudience().name(),
                name -> whatsAppChannel.setAudience(WhatsAppChannelPolicy.Audience.valueOf(name)));
    }
}
