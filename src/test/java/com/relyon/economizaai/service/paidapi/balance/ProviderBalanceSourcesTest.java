package com.relyon.economizaai.service.paidapi.balance;

import com.relyon.economizaai.config.CaptchaProperties;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.paidapi.CostReportService;
import com.relyon.economizaai.service.paidapi.PaidApiCallCosts;
import com.relyon.economizaai.service.sefaz.InfosimplesService;
import com.relyon.economizaai.service.sefaz.InfosimplesService.InfosimplesAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class ProviderBalanceSourcesTest {

    private MockRestServiceServer server;
    private RestClient restClient;
    private PaidApiCallCosts callCosts;
    private CostReportService costReport;

    @BeforeEach
    void setUp() {
        callCosts = new PaidApiCallCosts();
        costReport = mock(CostReportService.class);
        when(costReport.getUsdBrlRate()).thenReturn(new BigDecimal("5.50"));
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private static CaptchaProperties captcha(String provider, String apiKey) {
        var properties = new CaptchaProperties();
        properties.setProvider(provider);
        properties.setApiKey(apiKey);
        return properties;
    }

    // ---- CapSolver ----

    @Test
    void capSolver_readsTheBalance_postingTheClientKey() {
        server.expect(requestTo("https://api.capsolver.com/getBalance"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"clientKey\":\"CAP-KEY\"}"))
                .andRespond(withSuccess("{\"errorId\":0,\"balance\":12.345,\"packages\":[]}", MediaType.APPLICATION_JSON));
        var source = new CapSolverBalanceSource(restClient, captcha("capsolver", "CAP-KEY"), callCosts, costReport);

        assertEquals(0, source.fetchBalance().orElseThrow().compareTo(new BigDecimal("12.345")));
        server.verify();
    }

    @Test
    void capSolver_providerError_isUnreadable_notZero() {
        server.expect(requestTo("https://api.capsolver.com/getBalance"))
                .andRespond(withSuccess("{\"errorId\":1,\"errorCode\":\"ERROR_KEY_DENIED_ACCESS\"}", MediaType.APPLICATION_JSON));
        var source = new CapSolverBalanceSource(restClient, captcha("capsolver", "CAP-KEY"), callCosts, costReport);

        assertTrue(source.fetchBalance().isEmpty());
    }

    @Test
    void capSolver_httpFailure_isUnreadable_neverThrows() {
        server.expect(requestTo("https://api.capsolver.com/getBalance")).andRespond(withServerError());
        var source = new CapSolverBalanceSource(restClient, captcha("capsolver", "CAP-KEY"), callCosts, costReport);

        assertTrue(source.fetchBalance().isEmpty());
    }

    @Test
    void capSolver_isConfiguredOnlyWhenItIsTheSolverAndHasAKey() {
        assertTrue(new CapSolverBalanceSource(restClient, captcha("capsolver", "CAP-KEY"), callCosts, costReport).isConfigured());
        assertFalse(new CapSolverBalanceSource(restClient, captcha("none", "CAP-KEY"), callCosts, costReport).isConfigured());
        assertFalse(new CapSolverBalanceSource(restClient, captcha("capsolver", ""), callCosts, costReport).isConfigured());
    }

    // ---- Twilio ----

    @Test
    void twilio_readsTheAccountBalance_withBasicAuth() {
        var expectedAuth = "Basic " + Base64.getEncoder().encodeToString("AC123:secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Balance.json"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", expectedAuth))
                .andRespond(withSuccess("{\"account_sid\":\"AC123\",\"balance\":\"18.7412\",\"currency\":\"USD\"}",
                        MediaType.APPLICATION_JSON));
        var source = new TwilioBalanceSource(restClient, "AC123", "secret", callCosts, costReport);

        assertEquals(0, source.fetchBalance().orElseThrow().compareTo(new BigDecimal("18.7412")));
        assertEquals("US$", source.currencySymbol());
        server.verify();
    }

    @Test
    void twilio_authFailure_isUnreadable_neverThrows() {
        server.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Balance.json"))
                .andRespond(withUnauthorizedRequest());
        var source = new TwilioBalanceSource(restClient, "AC123", "secret", callCosts, costReport);

        assertTrue(source.fetchBalance().isEmpty());
    }

    @Test
    void twilio_withoutCredentials_isNotConfigured() {
        assertFalse(new TwilioBalanceSource(restClient, "", "", callCosts, costReport).isConfigured());
        assertFalse(new TwilioBalanceSource(restClient, "AC123", " ", callCosts, costReport).isConfigured());
        assertTrue(new TwilioBalanceSource(restClient, "AC123", "secret", callCosts, costReport).isConfigured());
    }

    // ---- Anthropic (no balance API: configured credit minus the usage ledger) ----

    @Test
    void anthropic_balanceIsConfiguredCreditMinusLedgerSpend() {
        var aiGateway = mock(AiGateway.class);
        var usageRepository = mock(AiUsageLogRepository.class);
        when(aiGateway.budgetUsd()).thenReturn(50.0);
        when(usageRepository.totalCostAllTime()).thenReturn(new BigDecimal("41.250000"));
        var source = new AnthropicCreditBalanceSource(aiGateway, usageRepository);

        assertTrue(source.isConfigured());
        assertEquals(0, source.configuredCredit().orElseThrow().compareTo(new BigDecimal("50")));
        assertEquals(0, source.fetchBalance().orElseThrow().compareTo(new BigDecimal("8.75")));
    }

    @Test
    void anthropic_creditUnset_isSkipped() {
        var aiGateway = mock(AiGateway.class);
        when(aiGateway.budgetUsd()).thenReturn(0.0);
        var source = new AnthropicCreditBalanceSource(aiGateway, mock(AiUsageLogRepository.class));

        assertFalse(source.isConfigured());
        assertTrue(source.fetchBalance().isEmpty());
    }

    // ---- Infosimples (reuses the account read of the finance panel) ----

    @Test
    void infosimples_readsTheSaldoFromTheAccountEndpoint() {
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchAccount()).thenReturn(Optional.of(
                new InfosimplesAccount(new BigDecimal("37.52"), new BigDecimal("12.00"), new BigDecimal("100"))));
        var source = new InfosimplesBalanceSource(Optional.of(infosimples), callCosts);

        assertTrue(source.isConfigured());
        assertEquals(0, source.fetchBalance().orElseThrow().compareTo(new BigDecimal("37.52")));
    }

    @Test
    void infosimples_disabledIntegration_isNotConfigured() {
        var source = new InfosimplesBalanceSource(Optional.empty(), callCosts);

        assertFalse(source.isConfigured());
        assertTrue(source.fetchBalance().isEmpty());
    }

    // ---- "exhausted" = the balance no longer pays for one more call ----

    @Test
    void exhaustedLine_isThePriceOfOneCall_inTheCurrencyOfTheBalance() {
        var infosimples = new InfosimplesBalanceSource(Optional.empty(), callCosts);
        var twilio = new TwilioBalanceSource(restClient, "AC123", "secret", callCosts, costReport);
        var capSolver = new CapSolverBalanceSource(restClient, captcha("capsolver", "CAP-KEY"), callCosts, costReport);

        assertEquals(new BigDecimal("0.24"), infosimples.exhaustedAtOrBelow());
        assertEquals(new BigDecimal("0.06"), twilio.exhaustedAtOrBelow());
        assertEquals(new BigDecimal("0.01"), capSolver.exhaustedAtOrBelow());

        callCosts.setInfosimples(40);
        callCosts.setTwilio(55);
        callCosts.setCaptcha(11);

        assertEquals(new BigDecimal("0.40"), infosimples.exhaustedAtOrBelow());
        assertEquals(new BigDecimal("0.10"), twilio.exhaustedAtOrBelow());
        assertEquals(new BigDecimal("0.02"), capSolver.exhaustedAtOrBelow());
    }
}
