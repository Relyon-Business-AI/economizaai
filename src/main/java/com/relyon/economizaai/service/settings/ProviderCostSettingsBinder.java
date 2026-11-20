package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.EcommerceProperties;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.paidapi.CostAlertService;
import com.relyon.economizaai.service.paidapi.PaidApiCallCosts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** What each paid call and AI model costs, when the cost warning goes out, and the online-offer rules. */
@Component
@RequiredArgsConstructor
public class ProviderCostSettingsBinder implements SettingBinder {

    private final PaidApiCallCosts callCosts;
    private final CostAlertService costAlerts;
    private final AiGateway aiGateway;
    private final EcommerceProperties onlineOffers;

    @Override
    public void bind(SettingBindings bindings) {
        bindCallCosts(bindings);
        bindings.integer(RuntimeSetting.COST_ALERT_WARNING_PERCENT,
                costAlerts::getWarningThresholdPercent, costAlerts::setWarningThresholdPercent);
        bindings.decimal(RuntimeSetting.AI_CREDIT_USD, aiGateway::getCreditUsd, aiGateway::setCreditUsd);
        bindings.text(RuntimeSetting.AI_MODEL_PRICES, aiGateway::getModelPricesText, aiGateway::setModelPricesText);
        bindings.toggle(RuntimeSetting.ONLINE_OFFERS_ENABLED, onlineOffers::isEnabled, onlineOffers::setEnabled);
        bindings.decimal(RuntimeSetting.ONLINE_OFFER_MIN_SAVINGS,
                onlineOffers::getWorthItMinSavings, onlineOffers::setWorthItMinSavings);
    }

    private void bindCallCosts(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.INFOSIMPLES_CALL_COST_CENTS, callCosts::getInfosimples, callCosts::setInfosimples);
        bindings.integer(RuntimeSetting.CAPTCHA_CALL_COST_CENTS, callCosts::getCaptcha, callCosts::setCaptcha);
        bindings.integer(RuntimeSetting.TWILIO_CALL_COST_CENTS, callCosts::getTwilio, callCosts::setTwilio);
        bindings.integer(RuntimeSetting.LLM_VISION_CALL_COST_CENTS, callCosts::getLlmVision, callCosts::setLlmVision);
        bindings.integer(RuntimeSetting.LLM_HTML_RESCUE_CALL_COST_CENTS,
                callCosts::getLlmHtmlRescue, callCosts::setLlmHtmlRescue);
        bindings.integer(RuntimeSetting.LLM_ENRICH_CALL_COST_CENTS, callCosts::getLlmEnrich, callCosts::setLlmEnrich);
    }
}
