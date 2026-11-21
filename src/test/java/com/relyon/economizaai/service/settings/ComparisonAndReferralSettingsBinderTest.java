package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.AppDefaultsProperties;
import com.relyon.economizaai.config.ComparisonProperties;
import com.relyon.economizaai.config.MonetizationProperties;
import com.relyon.economizaai.service.DataShareConsentService;
import com.relyon.economizaai.service.LeaderboardService;
import com.relyon.economizaai.service.merchant.MerchantPromoService;
import com.relyon.economizaai.service.savings.HouseholdSavingsService;
import com.relyon.economizaai.service.subscription.PremiumGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/** Each setting starts at the value the code always used and a change lands where the rule reads it. */
class ComparisonAndReferralSettingsBinderTest {

    private static final Map<RuntimeSetting, String> VALUE_BEFORE_THE_SETTING_EXISTED = Map.ofEntries(
            entry(RuntimeSetting.COMPARISON_MIN_MATCHED_PCT, "40"),
            entry(RuntimeSetting.RECEIPT_COMPARISON_MAX_AGE_DAYS, "7"),
            entry(RuntimeSetting.COMPARISON_ALTERNATIVE_MARKETS, "3"),
            entry(RuntimeSetting.ROUTE_DEFAULT_MAX_DISTANCE_KM, "10"),
            entry(RuntimeSetting.ROUTE_DEFAULT_MAX_STOPS, "2"),
            entry(RuntimeSetting.ROUTE_DEFAULT_MIN_SAVING_PER_STOP, "10"),
            entry(RuntimeSetting.HOME_SAVINGS_MIN, "10"),
            entry(RuntimeSetting.SAVINGS_DEFAULT_PERIOD_DAYS, "90"),
            entry(RuntimeSetting.LEADERBOARD_TOP_N, "20"),
            entry(RuntimeSetting.LEADERBOARD_DEFAULT_DAYS, "30"),
            entry(RuntimeSetting.REFERRAL_REWARD_MONTHS, "1"),
            entry(RuntimeSetting.PREMIUM_ASSUMED_LIFETIME_MONTHS, "12"),
            entry(RuntimeSetting.DATA_SHARE_CONSENT_DAYS, "14"),
            entry(RuntimeSetting.MERCHANT_VERIFIED_BADGE_TOLERANCE_PCT, "1"));

    private final ComparisonProperties comparison = new ComparisonProperties();
    private final AppDefaultsProperties appDefaults = new AppDefaultsProperties();
    private final MonetizationProperties monetization = new MonetizationProperties();
    private final HouseholdSavingsService householdSavings =
            new HouseholdSavingsService(null, null, null, null, null, null, null, null);
    private final LeaderboardService leaderboard = new LeaderboardService(null, null, null, null);
    private final PremiumGrantService premiumGrants = new PremiumGrantService(null, null, null);
    private final DataShareConsentService dataShareConsent = new DataShareConsentService(null, null, null, null, null);
    private final MerchantPromoService merchantPromos = new MerchantPromoService(null, null, null, null, null, null);
    private final SettingBindings bindings = new SettingBindings();

    @BeforeEach
    void bind() {
        new ComparisonSettingsBinder(comparison, appDefaults, householdSavings, leaderboard).bind(bindings);
        new ReferralSettingsBinder(premiumGrants, monetization, dataShareConsent, merchantPromos).bind(bindings);
    }

    @Test
    void everySettingStartsAtTheValueTheCodeAlwaysUsed_andAcceptsIt() {
        VALUE_BEFORE_THE_SETTING_EXISTED.forEach((setting, valueBefore) -> {
            assertThat(bindings.of(setting).currentValue().get()).as("%s", setting).isEqualTo(valueBefore);
            assertThat(SettingValueCodec.normalize(setting, valueBefore)).as("%s", setting).isEqualTo(valueBefore);
        });
    }

    @Test
    void aChangeLandsWhereTheRuleReadsIt() {
        change(RuntimeSetting.COMPARISON_MIN_MATCHED_PCT, "25");
        change(RuntimeSetting.RECEIPT_COMPARISON_MAX_AGE_DAYS, "10");
        change(RuntimeSetting.COMPARISON_ALTERNATIVE_MARKETS, "5");
        change(RuntimeSetting.ROUTE_DEFAULT_MAX_DISTANCE_KM, "7.5");
        change(RuntimeSetting.ROUTE_DEFAULT_MAX_STOPS, "3");
        change(RuntimeSetting.ROUTE_DEFAULT_MIN_SAVING_PER_STOP, "15");
        change(RuntimeSetting.HOME_SAVINGS_MIN, "5.50");
        change(RuntimeSetting.SAVINGS_DEFAULT_PERIOD_DAYS, "30");
        change(RuntimeSetting.LEADERBOARD_TOP_N, "10");
        change(RuntimeSetting.LEADERBOARD_DEFAULT_DAYS, "7");
        change(RuntimeSetting.REFERRAL_REWARD_MONTHS, "2");
        change(RuntimeSetting.PREMIUM_ASSUMED_LIFETIME_MONTHS, "18");
        change(RuntimeSetting.DATA_SHARE_CONSENT_DAYS, "5");
        change(RuntimeSetting.MERCHANT_VERIFIED_BADGE_TOLERANCE_PCT, "2.5");

        assertThat(comparison.getMinMatchedPct()).isEqualTo(25);
        assertThat(comparison.minMatchedItems(10)).isEqualTo(3);
        assertThat(comparison.getReceiptMaxAgeDays()).isEqualTo(10);
        assertThat(comparison.getAlternativeMarkets()).isEqualTo(5);
        assertThat(appDefaults.getRouteMaxDistanceKm()).isEqualByComparingTo("7.5");
        assertThat(appDefaults.getRouteMaxStops()).isEqualTo(3);
        assertThat(appDefaults.getRouteMinSavingPerStop()).isEqualByComparingTo("15");
        assertThat(appDefaults.getHomeSavingsMin()).isEqualByComparingTo("5.50");
        assertThat(householdSavings.getDefaultPeriodDays()).isEqualTo(30);
        assertThat(leaderboard.getTopN()).isEqualTo(10);
        assertThat(leaderboard.getDefaultDays()).isEqualTo(7);
        assertThat(premiumGrants.getReferralRewardMonths()).isEqualTo(2);
        assertThat(monetization.getAssumedLifetimeMonths()).isEqualTo(18);
        assertThat(monetization.ltvPerProUser()).isEqualByComparingTo(new BigDecimal("178.20"));
        assertThat(dataShareConsent.getConsentDays()).isEqualTo(5);
        assertThat(merchantPromos.getVerifiedBadgeTolerancePct()).isEqualByComparingTo("2.5");
    }

    @Test
    void theAppsReceiveTheNumbersInTheFormTheyRead() {
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.ROUTE_DEFAULT_MAX_STOPS, "2")).isEqualTo(2L);
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.ROUTE_DEFAULT_MAX_DISTANCE_KM, "10"))
                .isEqualTo(new BigDecimal("10"));
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.REFERRAL_REWARD_MONTHS, "1")).isEqualTo(1L);
    }

    private void change(RuntimeSetting setting, String typedValue) {
        bindings.of(setting).replaceValue().accept(SettingValueCodec.normalize(setting, typedValue));
    }
}
