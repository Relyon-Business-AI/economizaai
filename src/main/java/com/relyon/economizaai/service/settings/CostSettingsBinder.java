package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.paidapi.CostReportService;
import com.relyon.economizaai.service.paidapi.InfosimplesAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Paid-API spend ceilings, per user and per service. */
@Component
@RequiredArgsConstructor
public class CostSettingsBinder implements SettingBinder {

    private final PaidApiGuardProperties paidApi;
    private final AiGateway aiGateway;
    private final CostReportService costReport;
    private final InfosimplesAlertService infosimplesAlerts;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.toggle(RuntimeSetting.PAID_API_GUARD_ENABLED, paidApi::isEnabled, paidApi::setEnabled);
        bindPerUserCaps(bindings);
        bindDailyBudgets(bindings);
        bindings.integer(RuntimeSetting.AI_DAILY_REQUEST_CAP, aiGateway::getDailyRequestCap, aiGateway::setDailyRequestCap);
        bindings.decimal(RuntimeSetting.USD_BRL_RATE, costReport::getUsdBrlRate, costReport::setUsdBrlRate);
        bindings.decimal(RuntimeSetting.INFOSIMPLES_LOW_BALANCE,
                infosimplesAlerts::getLowBalanceThreshold, infosimplesAlerts::setLowBalanceThreshold);
    }

    private void bindPerUserCaps(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.INFOSIMPLES_DAILY_CAP_PER_USER,
                paidApi::getInfosimplesDailyCapPerUser, paidApi::setInfosimplesDailyCapPerUser);
        bindings.integer(RuntimeSetting.CAPTCHA_DAILY_CAP_PER_USER,
                paidApi::getCaptchaDailyCapPerUser, paidApi::setCaptchaDailyCapPerUser);
        bindings.integer(RuntimeSetting.TWILIO_DAILY_CAP_PER_USER,
                paidApi::getTwilioDailyCapPerUser, paidApi::setTwilioDailyCapPerUser);
        bindings.integer(RuntimeSetting.LLM_VISION_DAILY_CAP_PER_USER,
                paidApi::getLlmVisionDailyCapPerUser, paidApi::setLlmVisionDailyCapPerUser);
        bindings.integer(RuntimeSetting.LLM_HTML_RESCUE_DAILY_CAP_PER_USER,
                paidApi::getLlmHtmlRescueDailyCapPerUser, paidApi::setLlmHtmlRescueDailyCapPerUser);
        bindings.integer(RuntimeSetting.LLM_ENRICH_DAILY_CAP_PER_USER,
                paidApi::getLlmEnrichDailyCapPerUser, paidApi::setLlmEnrichDailyCapPerUser);
    }

    private void bindDailyBudgets(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.INFOSIMPLES_DAILY_BUDGET_CENTS,
                paidApi::getInfosimplesDailyBudgetCents, paidApi::setInfosimplesDailyBudgetCents);
        bindings.integer(RuntimeSetting.CAPTCHA_DAILY_BUDGET_CENTS,
                paidApi::getCaptchaDailyBudgetCents, paidApi::setCaptchaDailyBudgetCents);
        bindings.integer(RuntimeSetting.LLM_VISION_DAILY_BUDGET_CENTS,
                paidApi::getLlmVisionDailyBudgetCents, paidApi::setLlmVisionDailyBudgetCents);
        bindings.integer(RuntimeSetting.LLM_HTML_RESCUE_DAILY_BUDGET_CENTS,
                paidApi::getLlmHtmlRescueDailyBudgetCents, paidApi::setLlmHtmlRescueDailyBudgetCents);
        bindings.integer(RuntimeSetting.LLM_ENRICH_DAILY_BUDGET_CENTS,
                paidApi::getLlmEnrichDailyBudgetCents, paidApi::setLlmEnrichDailyBudgetCents);
        bindings.integer(RuntimeSetting.TWILIO_DAILY_BUDGET_CENTS,
                paidApi::getTwilioDailyBudgetCents, paidApi::setTwilioDailyBudgetCents);
        bindings.integer(RuntimeSetting.DAILY_GLOBAL_BUDGET_CENTS,
                paidApi::getDailyGlobalBudgetCents, paidApi::setDailyGlobalBudgetCents);
    }
}
