package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.AppDefaultsProperties;
import com.relyon.economizaai.config.ComparisonProperties;
import com.relyon.economizaai.service.LeaderboardService;
import com.relyon.economizaai.service.savings.HouseholdSavingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Purchase comparisons, the shopping plan's starting rules, the savings period and the ranking. */
@Component
@RequiredArgsConstructor
public class ComparisonSettingsBinder implements SettingBinder {

    private final ComparisonProperties comparison;
    private final AppDefaultsProperties appDefaults;
    private final HouseholdSavingsService householdSavings;
    private final LeaderboardService leaderboard;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.COMPARISON_MIN_MATCHED_PCT, comparison::getMinMatchedPct, comparison::setMinMatchedPct);
        bindings.integer(RuntimeSetting.RECEIPT_COMPARISON_MAX_AGE_DAYS,
                comparison::getReceiptMaxAgeDays, comparison::setReceiptMaxAgeDays);
        bindings.integer(RuntimeSetting.COMPARISON_ALTERNATIVE_MARKETS,
                comparison::getAlternativeMarkets, comparison::setAlternativeMarkets);

        bindings.decimal(RuntimeSetting.ROUTE_DEFAULT_MAX_DISTANCE_KM,
                appDefaults::getRouteMaxDistanceKm, appDefaults::setRouteMaxDistanceKm);
        bindings.integer(RuntimeSetting.ROUTE_DEFAULT_MAX_STOPS, appDefaults::getRouteMaxStops, appDefaults::setRouteMaxStops);
        bindings.decimal(RuntimeSetting.ROUTE_DEFAULT_MIN_SAVING_PER_STOP,
                appDefaults::getRouteMinSavingPerStop, appDefaults::setRouteMinSavingPerStop);
        bindings.decimal(RuntimeSetting.HOME_SAVINGS_MIN, appDefaults::getHomeSavingsMin, appDefaults::setHomeSavingsMin);

        bindings.integer(RuntimeSetting.SAVINGS_DEFAULT_PERIOD_DAYS,
                householdSavings::getDefaultPeriodDays, householdSavings::setDefaultPeriodDays);
        bindings.integer(RuntimeSetting.LEADERBOARD_TOP_N, leaderboard::getTopN, leaderboard::setTopN);
        bindings.integer(RuntimeSetting.LEADERBOARD_DEFAULT_DAYS, leaderboard::getDefaultDays, leaderboard::setDefaultDays);
    }
}
