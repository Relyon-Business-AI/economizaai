package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.config.MonetizationProperties;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Plans and subscription: free-tier limits, prices, launch promo. */
@Component
@RequiredArgsConstructor
public class PlanSettingsBinder implements SettingBinder {

    private final CollaborativeProperties properties;
    private final MonetizationProperties monetization;
    private final MercadoPagoProperties mercadoPago;

    @Override
    public void bind(SettingBindings bindings) {
        var subscription = properties.getSubscription();
        var free = subscription.getFree();
        var promo = subscription.getPromo();

        bindings.toggle(RuntimeSetting.SUBSCRIPTION_ENFORCE, subscription::isEnforce, subscription::setEnforce);
        bindings.integer(RuntimeSetting.FREE_MONTHLY_RECEIPTS, free::getMonthlyReceipts, free::setMonthlyReceipts);
        bindings.integer(RuntimeSetting.FREE_HISTORY_DAYS, free::getHistoryDays, free::setHistoryDays);
        bindings.integer(RuntimeSetting.FREE_WATCHED_MARKETS, free::getWatchedMarkets, free::setWatchedMarkets);
        bindings.integer(RuntimeSetting.FREE_HOUSEHOLD_MEMBERS, free::getHouseholdMembers, free::setHouseholdMembers);
        bindings.integer(RuntimeSetting.FREE_CUSTOM_CATEGORIES, free::getCustomCategories, free::setCustomCategories);
        bindings.text(RuntimeSetting.FREE_OPEN_FEATURES,
                () -> featureNames(free.getOpenFeatures()),
                names -> free.setOpenFeatures(featuresNamed(names)));
        bindings.integer(RuntimeSetting.HOUSEHOLD_MEMBERS_MAX,
                subscription::getHouseholdMembersMax, subscription::setHouseholdMembersMax);
        bindings.integer(RuntimeSetting.PAID_GRACE_DAYS, subscription::getPaidGraceDays, subscription::setPaidGraceDays);

        bindings.decimal(RuntimeSetting.PREMIUM_MONTHLY_PRICE, mercadoPago::getPlanAmount, this::changeMonthlyPrice);
        bindings.decimal(RuntimeSetting.PREMIUM_YEARLY_PRICE, monetization::getYearlyPrice, monetization::setYearlyPrice);

        bindings.toggle(RuntimeSetting.LAUNCH_PROMO_ENABLED, promo::isEnabled, promo::setEnabled);
        bindings.date(RuntimeSetting.LAUNCH_PROMO_UNTIL, promo::getUntil, promo::setUntil);
        bindings.integer(RuntimeSetting.LAUNCH_PROMO_MONTHS, promo::getMonths, promo::setMonths);
        bindings.toggle(RuntimeSetting.LAUNCH_PROMO_WARNINGS_ENABLED, promo::isWarningsEnabled, promo::setWarningsEnabled);
        bindings.integerList(RuntimeSetting.LAUNCH_PROMO_WARNING_DAYS, promo::getWarningDays, promo::setWarningDays);
    }

    /** One monthly price: what the web checkout charges and what the revenue projections use. */
    private void changeMonthlyPrice(BigDecimal monthlyPrice) {
        mercadoPago.setPlanAmount(monthlyPrice);
        monetization.setMonthlyPrice(monthlyPrice);
    }

    private static String featureNames(Set<Feature> features) {
        return features.stream().sorted().map(Feature::name).collect(Collectors.joining(","));
    }

    private static Set<Feature> featuresNamed(String names) {
        var features = EnumSet.noneOf(Feature.class);
        SettingValueCodec.splitList(names).forEach(name -> features.add(Feature.valueOf(name)));
        return features;
    }
}
