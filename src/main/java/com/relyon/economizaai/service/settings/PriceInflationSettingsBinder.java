package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.config.PriceInflationProperties.Audience;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;

/** "Preços e inflação": who sees it, the periods offered and how much data a number needs. */
@Component
@RequiredArgsConstructor
public class PriceInflationSettingsBinder implements SettingBinder {

    private final PriceInflationProperties priceInflation;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.text(RuntimeSetting.PRICE_INFLATION_AUDIENCE,
                () -> priceInflation.getAudience().name(),
                audience -> priceInflation.setAudience(Audience.valueOf(audience)));
        bindings.integerList(RuntimeSetting.PRICE_INFLATION_PERIODS, priceInflation::getPeriods, priceInflation::setPeriods);
        bindings.text(RuntimeSetting.PRICE_INFLATION_FOOD_CATEGORIES,
                () -> String.join(",", priceInflation.getFoodCategories()),
                slugs -> priceInflation.setFoodCategories(new LinkedHashSet<>(SettingValueCodec.splitList(slugs))));
        bindings.integer(RuntimeSetting.PRICE_INFLATION_MIN_PAIRS_PER_MONTH,
                priceInflation::getMinPairsPerMonth, priceInflation::setMinPairsPerMonth);
        bindings.integer(RuntimeSetting.PRICE_INFLATION_MIN_STORES_PER_MONTH,
                priceInflation::getMinStoresPerMonth, priceInflation::setMinStoresPerMonth);
        bindings.integer(RuntimeSetting.PRICE_INFLATION_MIN_PAIRS_PER_ITEM,
                priceInflation::getMinPairsPerItem, priceInflation::setMinPairsPerItem);
        bindings.integer(RuntimeSetting.PRICE_INFLATION_MAX_MONTHLY_CHANGE_PERCENT,
                priceInflation::getMaxMonthlyChangePercent, priceInflation::setMaxMonthlyChangePercent);
        bindings.integer(RuntimeSetting.PRICE_INFLATION_BASKET_MIN_PRODUCTS,
                priceInflation::getBasketMinProducts, priceInflation::setBasketMinProducts);
        bindings.toggle(RuntimeSetting.PRICE_INFLATION_MONTHLY_NOTICE,
                priceInflation::isMonthlyNotice, priceInflation::setMonthlyNotice);
    }
}
