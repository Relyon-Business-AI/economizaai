package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.EcommerceProperties;
import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.model.CostAlert;
import com.relyon.economizaai.model.EcommerceOffer;
import com.relyon.economizaai.model.PaidApiCall;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import com.relyon.economizaai.repository.CostAlertRepository;
import com.relyon.economizaai.repository.EcommerceOfferRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository.ServiceSpend;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.ai.AiProviderSettingsService;
import com.relyon.economizaai.service.ecommerce.EcommerceOfferService;
import com.relyon.economizaai.service.ecommerce.MercadoLivreProvider;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import com.relyon.economizaai.service.paidapi.CostAlertService;
import com.relyon.economizaai.service.paidapi.PaidApiBudgetMonitor;
import com.relyon.economizaai.service.paidapi.PaidApiCallCosts;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.paidapi.balance.AnthropicCreditBalanceSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each setting of this binder, changed the way the admin changes it, moves the code that uses it. */
class ProviderCostSettingsBinderTest {

    private static final String TODAYS_MODEL_PRICES = "claude-haiku-4-5=1/5;default=3/15";

    private final PaidApiCallCosts callCosts = new PaidApiCallCosts();
    private final CostAlertRepository costAlertRepository = mock(CostAlertRepository.class);
    private final CostAlertService costAlerts = new CostAlertService(costAlertRepository,
            mock(OperationalAlertService.class), true, 3, 8, 120_000, 80);
    private final AiUsageLogRepository aiUsageRepository = mock(AiUsageLogRepository.class);
    private final AiGateway aiGateway = new AiGateway(List.of(), mock(AiProviderSettingsService.class), aiUsageRepository);
    private final EcommerceProperties onlineOffers = new EcommerceProperties();
    private final SettingBindings bindings = new SettingBindings();

    @BeforeEach
    void bindTheSettings() {
        aiGateway.setCreditUsd(BigDecimal.ZERO);
        aiGateway.setModelPricesText(TODAYS_MODEL_PRICES);
        new ProviderCostSettingsBinder(callCosts, costAlerts, aiGateway, onlineOffers).bind(bindings);
    }

    private void adminSaves(RuntimeSetting setting, String typedValue) {
        bindings.of(setting).replaceValue().accept(SettingValueCodec.normalize(setting, typedValue));
    }

    private String valueInEffect(RuntimeSetting setting) {
        return bindings.of(setting).currentValue().get();
    }

    @ParameterizedTest
    @CsvSource({
            "INFOSIMPLES_CALL_COST_CENTS, INFOSIMPLES, 24",
            "CAPTCHA_CALL_COST_CENTS, CAPTCHA_SOLVE, 3",
            "TWILIO_CALL_COST_CENTS, TWILIO_MESSAGE, 30",
            "LLM_ENRICH_CALL_COST_CENTS, LLM_ENRICH, 2",
            "LLM_VISION_CALL_COST_CENTS, LLM_VISION, 10",
            "LLM_HTML_RESCUE_CALL_COST_CENTS, LLM_HTML_RESCUE, 5"
    })
    void callCost_isWhatTheLedgerRecordsForTheNextCall(RuntimeSetting setting, PaidApiService service, int todaysCents) {
        var ledger = mock(PaidApiCallRepository.class);
        var guard = new PaidApiGuardService(new PaidApiGuardProperties(), ledger, callCosts);
        assertThat(valueInEffect(setting)).isEqualTo(String.valueOf(todaysCents));

        guard.recordSuccess(null, service, null, null);
        adminSaves(setting, "77");
        guard.recordSuccess(null, service, null, null);

        var recorded = ArgumentCaptor.forClass(PaidApiCall.class);
        verify(ledger, times(2)).save(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(PaidApiCall::getEstimatedCostCents)
                .containsExactly(todaysCents, 77);
    }

    @Test
    void warningPercent_decidesWhenTheBudgetWarningGoesOut_andWhatItSays() {
        var ledger = mock(PaidApiCallRepository.class);
        var infosimplesSpend = mock(ServiceSpend.class);
        when(infosimplesSpend.getService()).thenReturn(PaidApiService.INFOSIMPLES);
        when(infosimplesSpend.getCostCents()).thenReturn(7_200L); // 60% of the R$120 daily budget
        when(ledger.spendByService(any())).thenReturn(List.of(infosimplesSpend));
        var monitor = new PaidApiBudgetMonitor(new PaidApiGuardProperties(), ledger, costAlerts);
        assertThat(valueInEffect(RuntimeSetting.COST_ALERT_WARNING_PERCENT)).isEqualTo("80");

        monitor.checkDailyBudgets();
        verify(costAlertRepository, never()).save(any(CostAlert.class));

        adminSaves(RuntimeSetting.COST_ALERT_WARNING_PERCENT, "60");
        monitor.checkDailyBudgets();

        var raised = ArgumentCaptor.forClass(CostAlert.class);
        verify(costAlertRepository).save(raised.capture());
        assertThat(raised.getValue().getThreshold()).isEqualTo(60);
        assertThat(raised.getValue().getHeadline()).contains("Infosimples em 60%");
    }

    @Test
    void aiCredit_isTheReferenceOfTheAiBalance() {
        when(aiUsageRepository.totalCostAllTime()).thenReturn(new BigDecimal("12.50"));
        var aiBalance = new AnthropicCreditBalanceSource(aiGateway, aiUsageRepository);
        assertThat(valueInEffect(RuntimeSetting.AI_CREDIT_USD)).isEqualTo("0");
        assertThat(aiBalance.isConfigured()).isFalse();

        adminSaves(RuntimeSetting.AI_CREDIT_USD, "50");

        assertThat(aiBalance.isConfigured()).isTrue();
        assertThat(aiBalance.fetchBalance().orElseThrow()).isEqualByComparingTo("37.50");
    }

    @Test
    void aiModelPrices_areTheTableTheGatewayEstimatesWith() {
        assertThat(valueInEffect(RuntimeSetting.AI_MODEL_PRICES)).isEqualTo(TODAYS_MODEL_PRICES);

        adminSaves(RuntimeSetting.AI_MODEL_PRICES, "claude-haiku-4-5 = 2/8; default = 4/20");

        assertThat(aiGateway.getModelPricesText()).isEqualTo("claude-haiku-4-5=2/8;default=4/20");
    }

    @Test
    void onlineOffersToggle_turnsTheConnectedStoreOnAndOffWithoutARestart() {
        var mercadoLivre = new EcommerceProperties.Provider();
        mercadoLivre.setEnabled(true);
        mercadoLivre.setClientId("app-id");
        mercadoLivre.setClientSecret("secret");
        onlineOffers.getProviders().put("mercadolivre", mercadoLivre);
        var store = new MercadoLivreProvider(onlineOffers, RestClient.builder());
        assertThat(valueInEffect(RuntimeSetting.ONLINE_OFFERS_ENABLED)).isEqualTo("false");
        assertThat(store.isConfigured()).isFalse();

        adminSaves(RuntimeSetting.ONLINE_OFFERS_ENABLED, "true");
        assertThat(store.isConfigured()).isTrue();

        adminSaves(RuntimeSetting.ONLINE_OFFERS_ENABLED, "false");
        assertThat(store.isConfigured()).isFalse();
    }

    @Test
    void onlineOfferMinSavings_decidesTheWorthItSeal() {
        var offers = mock(EcommerceOfferRepository.class);
        when(offers.findByEanAndActiveTrue("789")).thenReturn(List.of(EcommerceOffer.builder()
                .ean("789").provider("mercadolivre").title("Café online").price(new BigDecimal("15.00"))
                .currency("BRL").inStock(true).active(true).curated(true).build()));
        var offerService = new EcommerceOfferService(offers, onlineOffers, List.of());
        var boughtFor20 = ReceiptItem.builder().ean("789").paidUnitPrice(new BigDecimal("20.00")).build();
        assertThat(valueInEffect(RuntimeSetting.ONLINE_OFFER_MIN_SAVINGS)).isEqualTo("0");

        assertThat(offerService.bestOfferForItem(boughtFor20, null).orElseThrow().worthIt()).isTrue();
        adminSaves(RuntimeSetting.ONLINE_OFFER_MIN_SAVINGS, "7,50");
        assertThat(offerService.bestOfferForItem(boughtFor20, null).orElseThrow().worthIt()).isFalse();
    }
}
