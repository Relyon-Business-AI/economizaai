package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.InvalidSettingValueException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each setting of this binder starts at the value the code used before it became a setting, and
 * a value saved in the admin lands in the very field its job reads.
 */
class ScheduleSettingsBinderTest {

    private final CollaborativeProperties properties = new CollaborativeProperties();
    private final DealsDigestScheduler dealsDigest =
            new DealsDigestScheduler(null, null, null, null, null, null, null, properties, null);
    private final DigestScheduleService digestSchedule = new DigestScheduleService(null, null);
    private final ReplenishmentService replenishment = new ReplenishmentService(null, null, null, null, null);
    private final BudgetMonitorService budgetMonitor = new BudgetMonitorService(null, null, null, null, null, null);
    private final SpendingReportScheduler spendingReports = new SpendingReportScheduler(null, null, null, null, false);
    private final PushOptInNudgeScheduler pushOptInNudge = new PushOptInNudgeScheduler(null, null, null);
    private final NotificationRuleService notificationRules = new NotificationRuleService(null, null, null);
    private final SavingsService savings = new SavingsService(null, null);
    private final SignupAlertService signupAlert = new SignupAlertService(null, null, true);
    private final OperationalAlertService operationalAlerts = new OperationalAlertService(null, null, null, "", "", true);

    private final SettingBindings bindings = new SettingBindings();

    private static final Map<RuntimeSetting, String> VALUE_BEFORE_IT_WAS_A_SETTING = Map.ofEntries(
            entry(RuntimeSetting.DEALS_DIGEST_WEEKLY_DAY, "THURSDAY"),
            entry(RuntimeSetting.DEALS_DIGEST_IMPROVEMENT_POINTS, "5"),
            entry(RuntimeSetting.DEALS_DIGEST_DEFAULT_HOUR_FROM, "11"),
            entry(RuntimeSetting.DEALS_DIGEST_DEFAULT_HOUR_TO, "19"),
            entry(RuntimeSetting.DEALS_DIGEST_MIN_RECEIPTS_FOR_HABIT, "5"),
            entry(RuntimeSetting.REPLENISHMENT_SEND_HOUR, "10"),
            entry(RuntimeSetting.BUDGET_CHECK_HOURS, "9,18"),
            entry(RuntimeSetting.SPENDING_REPORT_WEEKLY_DAY, "MONDAY"),
            entry(RuntimeSetting.SPENDING_REPORT_WEEKLY_HOUR, "10"),
            entry(RuntimeSetting.SPENDING_REPORT_MONTHLY_DAY, "1"),
            entry(RuntimeSetting.SPENDING_REPORT_MONTHLY_HOUR, "10"),
            entry(RuntimeSetting.PUSH_OPTIN_NUDGE_WEEKDAY, "TUESDAY"),
            entry(RuntimeSetting.PUSH_OPTIN_NUDGE_HOUR, "12"),
            entry(RuntimeSetting.PUSH_OPTIN_RENUDGE_DAYS, "30"),
            entry(RuntimeSetting.STOCKOUT_DEFAULT_LEAD_DAYS, "3"),
            entry(RuntimeSetting.SAVINGS_RECENT_DAYS, "30"),
            entry(RuntimeSetting.CONSUMPTION_HIGH_CONFIDENCE_SAMPLES, "8"),
            entry(RuntimeSetting.CONSUMPTION_MEDIUM_CONFIDENCE_SAMPLES, "5"),
            entry(RuntimeSetting.PREFERENCES_HIGH_CONFIDENCE_PURCHASES, "15"),
            entry(RuntimeSetting.PREFERENCES_MEDIUM_CONFIDENCE_PURCHASES, "8"),
            entry(RuntimeSetting.SIGNUP_ALERT_ENABLED, "true"),
            entry(RuntimeSetting.ADMIN_WHATSAPP_ENABLED, "true"));

    @BeforeEach
    void bind() {
        new ScheduleSettingsBinder(dealsDigest, digestSchedule, replenishment, budgetMonitor, spendingReports,
                pushOptInNudge, notificationRules, savings, properties, signupAlert, operationalAlerts).bind(bindings);
    }

    private void save(RuntimeSetting setting, String typedInTheAdmin) {
        bindings.of(setting).replaceValue().accept(SettingValueCodec.normalize(setting, typedInTheAdmin));
    }

    @Test
    void everySettingStartsAtTheValueTheCodeUsedBefore() {
        VALUE_BEFORE_IT_WAS_A_SETTING.forEach((setting, valueBefore) ->
                assertThat(bindings.of(setting).currentValue().get()).as("%s", setting).isEqualTo(valueBefore));
    }

    @Test
    void everyDefaultIsAValueTheAdminAccepts() {
        VALUE_BEFORE_IT_WAS_A_SETTING.forEach((setting, valueBefore) ->
                assertThat(SettingValueCodec.normalize(setting, valueBefore)).as("%s", setting).isEqualTo(valueBefore));
    }

    @Test
    void sendTimesSavedInTheAdminReachTheJobs() {
        save(RuntimeSetting.REPLENISHMENT_SEND_HOUR, "15");
        save(RuntimeSetting.BUDGET_CHECK_HOURS, "7, 12,20");
        save(RuntimeSetting.SPENDING_REPORT_WEEKLY_DAY, "friday");
        save(RuntimeSetting.SPENDING_REPORT_WEEKLY_HOUR, "8");
        save(RuntimeSetting.SPENDING_REPORT_MONTHLY_DAY, "5");
        save(RuntimeSetting.SPENDING_REPORT_MONTHLY_HOUR, "14");
        save(RuntimeSetting.PUSH_OPTIN_NUDGE_WEEKDAY, "SATURDAY");
        save(RuntimeSetting.PUSH_OPTIN_NUDGE_HOUR, "18");
        save(RuntimeSetting.PUSH_OPTIN_RENUDGE_DAYS, "45");

        assertThat(replenishment.getSendHour()).isEqualTo(15);
        assertThat(budgetMonitor.getCheckHours()).containsExactly(7, 12, 20);
        assertThat(spendingReports.getWeeklyDay()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(spendingReports.getWeeklyHour()).isEqualTo(8);
        assertThat(spendingReports.getMonthlyDay()).isEqualTo(5);
        assertThat(spendingReports.getMonthlyHour()).isEqualTo(14);
        assertThat(pushOptInNudge.getWeekday()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(pushOptInNudge.getSendHour()).isEqualTo(18);
        assertThat(pushOptInNudge.getRenudgeDays()).isEqualTo(45);
    }

    @Test
    void dealsDigestValuesSavedInTheAdminReachTheDigest() {
        save(RuntimeSetting.DEALS_DIGEST_WEEKLY_DAY, "SUNDAY");
        save(RuntimeSetting.DEALS_DIGEST_IMPROVEMENT_POINTS, "8");
        save(RuntimeSetting.DEALS_DIGEST_DEFAULT_HOUR_FROM, "9");
        save(RuntimeSetting.DEALS_DIGEST_DEFAULT_HOUR_TO, "21");
        save(RuntimeSetting.DEALS_DIGEST_MIN_RECEIPTS_FOR_HABIT, "10");

        assertThat(dealsDigest.getWeeklyDay()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(dealsDigest.getImprovementPoints()).isEqualTo(8);
        assertThat(digestSchedule.getSpreadStartHour()).isEqualTo(9);
        assertThat(digestSchedule.getSpreadEndHour()).isEqualTo(21);
        assertThat(digestSchedule.getMinReceiptsForInference()).isEqualTo(10);
    }

    @Test
    void theOtherValuesSavedInTheAdminReachTheirCode() {
        save(RuntimeSetting.STOCKOUT_DEFAULT_LEAD_DAYS, "5");
        save(RuntimeSetting.SAVINGS_RECENT_DAYS, "7");
        save(RuntimeSetting.CONSUMPTION_HIGH_CONFIDENCE_SAMPLES, "12");
        save(RuntimeSetting.CONSUMPTION_MEDIUM_CONFIDENCE_SAMPLES, "6");
        save(RuntimeSetting.PREFERENCES_HIGH_CONFIDENCE_PURCHASES, "20");
        save(RuntimeSetting.PREFERENCES_MEDIUM_CONFIDENCE_PURCHASES, "10");
        save(RuntimeSetting.SIGNUP_ALERT_ENABLED, "false");
        save(RuntimeSetting.ADMIN_WHATSAPP_ENABLED, "false");

        assertThat(notificationRules.getDefaultLeadTimeDays()).isEqualTo(5);
        assertThat(savings.getRecentSavingsDays()).isEqualTo(7);
        assertThat(properties.getConsumption().getHighConfidenceMinSamples()).isEqualTo(12);
        assertThat(properties.getConsumption().getMediumConfidenceMinSamples()).isEqualTo(6);
        assertThat(properties.getPreferences().getHighConfidenceMinPurchases()).isEqualTo(20);
        assertThat(properties.getPreferences().getMediumConfidenceMinPurchases()).isEqualTo(10);
        assertThat(signupAlert.isEnabled()).isFalse();
        assertThat(operationalAlerts.isWhatsAppEnabled()).isFalse();
    }

    @Test
    void hoursAndDaysOutsideTheClockOrTheMonthAreRefused() {
        assertRefused(RuntimeSetting.REPLENISHMENT_SEND_HOUR, "24");
        assertRefused(RuntimeSetting.BUDGET_CHECK_HOURS, "9,25");
        assertRefused(RuntimeSetting.SPENDING_REPORT_MONTHLY_DAY, "31");
        assertRefused(RuntimeSetting.PUSH_OPTIN_NUDGE_WEEKDAY, "0 0 12 * * TUE");
        assertThat(replenishment.getSendHour()).isEqualTo(10);
        assertThat(budgetMonitor.getCheckHours()).isEqualTo(List.of(9, 18));
    }

    private void assertRefused(RuntimeSetting setting, String typedInTheAdmin) {
        assertThatThrownBy(() -> save(setting, typedInTheAdmin))
                .isInstanceOf(InvalidSettingValueException.class);
    }
}
