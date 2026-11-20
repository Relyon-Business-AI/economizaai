package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.service.auth.SignupAlertService;
import com.relyon.economizaai.service.notifications.NotificationRuleService;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import com.relyon.economizaai.service.notifications.SavingsService;
import com.relyon.economizaai.service.notifications.schedule.BudgetMonitorService;
import com.relyon.economizaai.service.notifications.schedule.DealsDigestScheduler;
import com.relyon.economizaai.service.notifications.schedule.DigestScheduleService;
import com.relyon.economizaai.service.notifications.schedule.PushOptInNudgeScheduler;
import com.relyon.economizaai.service.notifications.schedule.ReplenishmentService;
import com.relyon.economizaai.service.notifications.schedule.SpendingReportScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Send times of the scheduled notices, the deals digest, confidence labels and the owner's alerts. */
@Component
@RequiredArgsConstructor
public class ScheduleSettingsBinder implements SettingBinder {

    private final DealsDigestScheduler dealsDigest;
    private final DigestScheduleService digestSchedule;
    private final ReplenishmentService replenishment;
    private final BudgetMonitorService budgetMonitor;
    private final SpendingReportScheduler spendingReports;
    private final PushOptInNudgeScheduler pushOptInNudge;
    private final NotificationRuleService notificationRules;
    private final SavingsService savings;
    private final CollaborativeProperties properties;
    private final SignupAlertService signupAlert;
    private final OperationalAlertService operationalAlerts;

    @Override
    public void bind(SettingBindings bindings) {
        bindDealsDigest(bindings);
        bindSendTimes(bindings);
        bindConfidenceLabels(bindings);

        bindings.integer(RuntimeSetting.STOCKOUT_DEFAULT_LEAD_DAYS,
                notificationRules::getDefaultLeadTimeDays, notificationRules::setDefaultLeadTimeDays);
        bindings.integer(RuntimeSetting.SAVINGS_RECENT_DAYS, savings::getRecentSavingsDays, savings::setRecentSavingsDays);
        bindings.toggle(RuntimeSetting.SIGNUP_ALERT_ENABLED, signupAlert::isEnabled, signupAlert::setEnabled);
        bindings.toggle(RuntimeSetting.ADMIN_WHATSAPP_ENABLED,
                operationalAlerts::isWhatsAppEnabled, operationalAlerts::setWhatsAppEnabled);
    }

    private void bindDealsDigest(SettingBindings bindings) {
        weekday(bindings, RuntimeSetting.DEALS_DIGEST_WEEKLY_DAY, dealsDigest::getWeeklyDay, dealsDigest::setWeeklyDay);
        bindings.integer(RuntimeSetting.DEALS_DIGEST_IMPROVEMENT_POINTS,
                dealsDigest::getImprovementPoints, dealsDigest::setImprovementPoints);
        bindings.integer(RuntimeSetting.DEALS_DIGEST_DEFAULT_HOUR_FROM,
                digestSchedule::getSpreadStartHour, digestSchedule::setSpreadStartHour);
        bindings.integer(RuntimeSetting.DEALS_DIGEST_DEFAULT_HOUR_TO,
                digestSchedule::getSpreadEndHour, digestSchedule::setSpreadEndHour);
        bindings.integer(RuntimeSetting.DEALS_DIGEST_MIN_RECEIPTS_FOR_HABIT,
                digestSchedule::getMinReceiptsForInference, digestSchedule::setMinReceiptsForInference);
    }

    private void bindSendTimes(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.REPLENISHMENT_SEND_HOUR, replenishment::getSendHour, replenishment::setSendHour);
        bindings.integerList(RuntimeSetting.BUDGET_CHECK_HOURS,
                budgetMonitor::getCheckHours, hours -> budgetMonitor.setCheckHours(List.copyOf(hours)));

        weekday(bindings, RuntimeSetting.SPENDING_REPORT_WEEKLY_DAY, spendingReports::getWeeklyDay, spendingReports::setWeeklyDay);
        bindings.integer(RuntimeSetting.SPENDING_REPORT_WEEKLY_HOUR, spendingReports::getWeeklyHour, spendingReports::setWeeklyHour);
        bindings.integer(RuntimeSetting.SPENDING_REPORT_MONTHLY_DAY, spendingReports::getMonthlyDay, spendingReports::setMonthlyDay);
        bindings.integer(RuntimeSetting.SPENDING_REPORT_MONTHLY_HOUR,
                spendingReports::getMonthlyHour, spendingReports::setMonthlyHour);

        weekday(bindings, RuntimeSetting.PUSH_OPTIN_NUDGE_WEEKDAY, pushOptInNudge::getWeekday, pushOptInNudge::setWeekday);
        bindings.integer(RuntimeSetting.PUSH_OPTIN_NUDGE_HOUR, pushOptInNudge::getSendHour, pushOptInNudge::setSendHour);
        bindings.integer(RuntimeSetting.PUSH_OPTIN_RENUDGE_DAYS, pushOptInNudge::getRenudgeDays, pushOptInNudge::setRenudgeDays);
    }

    private void bindConfidenceLabels(SettingBindings bindings) {
        var consumption = properties.getConsumption();
        bindings.integer(RuntimeSetting.CONSUMPTION_HIGH_CONFIDENCE_SAMPLES,
                consumption::getHighConfidenceMinSamples, consumption::setHighConfidenceMinSamples);
        bindings.integer(RuntimeSetting.CONSUMPTION_MEDIUM_CONFIDENCE_SAMPLES,
                consumption::getMediumConfidenceMinSamples, consumption::setMediumConfidenceMinSamples);

        var preferences = properties.getPreferences();
        bindings.integer(RuntimeSetting.PREFERENCES_HIGH_CONFIDENCE_PURCHASES,
                preferences::getHighConfidenceMinPurchases, preferences::setHighConfidenceMinPurchases);
        bindings.integer(RuntimeSetting.PREFERENCES_MEDIUM_CONFIDENCE_PURCHASES,
                preferences::getMediumConfidenceMinPurchases, preferences::setMediumConfidenceMinPurchases);
    }

    private static void weekday(SettingBindings bindings, RuntimeSetting setting,
                                Supplier<DayOfWeek> currentValue, Consumer<DayOfWeek> replaceValue) {
        bindings.text(setting, () -> currentValue.get().name(), name -> replaceValue.accept(DayOfWeek.valueOf(name)));
    }
}
