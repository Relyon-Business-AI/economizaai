package com.relyon.economizaai.service.settings;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.service.ProductService;
import com.relyon.economizaai.service.notifications.RelevanceThreshold;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Community prices, promos, deals, savings and consumption forecasts. */
@Component
@RequiredArgsConstructor
public class PriceSettingsBinder implements SettingBinder {

    private final CollaborativeProperties properties;
    private final ProductService productService;

    @Value("${economizaai.notifications.relevance-threshold.max-drop-pct:20}")
    private int configuredMaxDropPct = RelevanceThreshold.Curve.DEFAULT.maxDropPct();
    @Value("${economizaai.notifications.relevance-threshold.min-drop-pct:5}")
    private int configuredMinDropPct = RelevanceThreshold.Curve.DEFAULT.minDropPct();
    @Value("${economizaai.notifications.relevance-threshold.low-price:1}")
    private BigDecimal configuredLowPrice = RelevanceThreshold.Curve.DEFAULT.lowPrice();
    @Value("${economizaai.notifications.relevance-threshold.high-price:200}")
    private BigDecimal configuredHighPrice = RelevanceThreshold.Curve.DEFAULT.highPrice();

    @Override
    public void bind(SettingBindings bindings) {
        bindPrices(bindings);
        bindDealRelevance(bindings);
        bindSavings(bindings);
        bindConsumption(bindings);
    }

    private void bindPrices(SettingBindings bindings) {
        var collaborative = properties.getCollaborative();
        var personalPromo = properties.getPersonalPromo();

        bindings.integer(RuntimeSetting.PRICE_WINDOW_DAYS, collaborative::getLookbackDays, collaborative::setLookbackDays);
        bindings.integer(RuntimeSetting.MARKET_PRICES_DEFAULT_DAYS,
                collaborative::getMarketPricesDefaultDays, collaborative::setMarketPricesDefaultDays);
        bindings.integer(RuntimeSetting.DEALS_MAX_PRICE_AGE_DAYS,
                collaborative::getDealsMaxPriceAgeDays, collaborative::setDealsMaxPriceAgeDays);
        bindings.fraction(RuntimeSetting.NEARBY_RADIUS_KM, collaborative::getBestMarketsNearbyRadiusKm, this::changeNearbyRadius);
        bindings.integer(RuntimeSetting.COMMUNITY_PROMO_THRESHOLD_PCT,
                collaborative::getCommunityPromoThresholdPct, collaborative::setCommunityPromoThresholdPct);
        bindings.integer(RuntimeSetting.COMMUNITY_PROMO_RECENT_WINDOW_DAYS,
                collaborative::getCommunityPromoRecentWindowDays, collaborative::setCommunityPromoRecentWindowDays);
        bindings.integer(RuntimeSetting.COMMUNITY_PROMO_MIN_OLD,
                collaborative::getCommunityPromoMinBaselineObservations, collaborative::setCommunityPromoMinBaselineObservations);
        bindings.integer(RuntimeSetting.COMMUNITY_PROMO_MIN_RECENT,
                collaborative::getCommunityPromoMinRecentObservations, collaborative::setCommunityPromoMinRecentObservations);
        bindings.integer(RuntimeSetting.MIN_OBSERVATIONS_PER_PRODUCT_MARKET,
                collaborative::getMinObservationsPerProductMarket, collaborative::setMinObservationsPerProductMarket);
        bindings.integer(RuntimeSetting.PERSONAL_PROMO_THRESHOLD_PCT,
                personalPromo::getThresholdPct, personalPromo::setThresholdPct);
        bindings.integer(RuntimeSetting.PERSONAL_PROMO_MIN_PURCHASES,
                personalPromo::getMinPurchasesForBaseline, personalPromo::setMinPurchasesForBaseline);
    }

    private void bindDealRelevance(SettingBindings bindings) {
        RelevanceThreshold.useCurve(new RelevanceThreshold.Curve(
                configuredMaxDropPct, configuredMinDropPct, configuredLowPrice, configuredHighPrice));
        bindings.integer(RuntimeSetting.DEAL_MAX_DROP_PCT, () -> RelevanceThreshold.curve().maxDropPct(),
                pct -> RelevanceThreshold.useCurve(RelevanceThreshold.curve().withMaxDropPct(pct)));
        bindings.integer(RuntimeSetting.DEAL_MIN_DROP_PCT, () -> RelevanceThreshold.curve().minDropPct(),
                pct -> RelevanceThreshold.useCurve(RelevanceThreshold.curve().withMinDropPct(pct)));
        bindings.decimal(RuntimeSetting.DEAL_LOW_PRICE_ANCHOR, () -> RelevanceThreshold.curve().lowPrice(),
                price -> RelevanceThreshold.useCurve(RelevanceThreshold.curve().withLowPrice(price)));
        bindings.decimal(RuntimeSetting.DEAL_HIGH_PRICE_ANCHOR, () -> RelevanceThreshold.curve().highPrice(),
                price -> RelevanceThreshold.useCurve(RelevanceThreshold.curve().withHighPrice(price)));
    }

    private void bindSavings(SettingBindings bindings) {
        var collaborative = properties.getCollaborative();
        var attribution = properties.getAttribution();

        bindings.integer(RuntimeSetting.SAVINGS_REGION_WINDOW_DAYS,
                collaborative::getSavingsRegionWindowDays, collaborative::setSavingsRegionWindowDays);
        bindings.integer(RuntimeSetting.SAVINGS_MIN_REGION_SAMPLES,
                collaborative::getSavingsMinRegionSamples, collaborative::setSavingsMinRegionSamples);
        bindings.integer(RuntimeSetting.SAVINGS_OWN_LOOKBACK_DAYS,
                collaborative::getSavingsOwnLookbackDays, collaborative::setSavingsOwnLookbackDays);
        bindings.integer(RuntimeSetting.SAVINGS_MIN_OWN_SAMPLES,
                collaborative::getSavingsMinOwnSamples, collaborative::setSavingsMinOwnSamples);
        bindings.fraction(RuntimeSetting.SAVINGS_MAX_PRICE_RATIO,
                collaborative::getSavingsMaxPriceRatio, collaborative::setSavingsMaxPriceRatio);
        bindings.integer(RuntimeSetting.ATTRIBUTION_WINDOW_DAYS, attribution::getWindowDays, attribution::setWindowDays);
        bindings.integer(RuntimeSetting.ATTRIBUTION_FOLLOW_UP_DAYS, attribution::getFollowUpDays, attribution::setFollowUpDays);
    }

    private void bindConsumption(SettingBindings bindings) {
        var consumption = properties.getConsumption();
        var preferences = properties.getPreferences();

        bindings.integer(RuntimeSetting.CONSUMPTION_MIN_PURCHASES,
                consumption::getMinPurchasesForPrediction, consumption::setMinPurchasesForPrediction);
        bindings.integer(RuntimeSetting.CONSUMPTION_FALLBACK_MIN_PURCHASES,
                consumption::getFallbackMinPurchasesForPrediction, consumption::setFallbackMinPurchasesForPrediction);
        bindings.integer(RuntimeSetting.CONSUMPTION_HISTORY_LOOKBACK_DAYS,
                consumption::getHistoryLookbackDays, consumption::setHistoryLookbackDays);
        bindings.integer(RuntimeSetting.CONSUMPTION_RUNNING_LOW_DAYS,
                consumption::getRunningLowThresholdDays, consumption::setRunningLowThresholdDays);
        bindings.integer(RuntimeSetting.CONSUMPTION_RAN_OUT_GRACE_DAYS,
                consumption::getRanOutGraceDays, consumption::setRanOutGraceDays);
        bindings.integer(RuntimeSetting.PREFERENCES_MIN_PURCHASES,
                preferences::getMinPurchasesPerGeneric, preferences::setMinPurchasesPerGeneric);
        bindings.fraction(RuntimeSetting.PREFERENCES_MUST_HAVE_SHARE,
                preferences::getMustHaveBrandShare, preferences::setMustHaveBrandShare);
        bindings.fraction(RuntimeSetting.PREFERENCES_PREFERRED_SHARE,
                preferences::getPreferredBrandShare, preferences::setPreferredBrandShare);
    }

    /** One "perto de você" radius: the best-markets/deals default and the product search. */
    private void changeNearbyRadius(double radiusKm) {
        properties.getCollaborative().setBestMarketsNearbyRadiusKm(radiusKm);
        productService.setNearbyRadiusKm(radiusKm);
    }
}
