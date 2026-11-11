package com.relyon.economizaai.service.paidapi.balance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/** Twilio account balance via the Balance resource ({@code GET /Accounts/{sid}/Balance.json}). */
@Slf4j
@Component
public class TwilioBalanceSource implements ProviderBalanceSource {

    private static final String API_BASE = "https://api.twilio.com/2010-04-01/Accounts/";
    private static final String US_DOLLAR = "USD";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String accountSid;
    private final String authToken;
    private volatile String currencySymbol = "US$";

    @Autowired
    public TwilioBalanceSource(RestClient.Builder builder,
                               @Value("${economizaai.notifications.twilio.account-sid:}") String accountSid,
                               @Value("${economizaai.notifications.twilio.auth-token:}") String authToken) {
        this(builder.requestFactory(timeoutFactory()).build(), accountSid, authToken);
    }

    /** Test seam: inject a pre-built (mock-bound) RestClient. */
    TwilioBalanceSource(RestClient restClient, String accountSid, String authToken) {
        this.restClient = restClient;
        this.accountSid = accountSid;
        this.authToken = authToken;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return requestFactory;
    }

    @Override
    public String providerKey() {
        return "TWILIO";
    }

    @Override
    public String displayName() {
        return "Twilio";
    }

    /** The currency of the last balance read (Twilio accounts are normally in US$). */
    @Override
    public String currencySymbol() {
        return currencySymbol;
    }

    @Override
    public boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank() && authToken != null && !authToken.isBlank();
    }

    @Override
    public Optional<BigDecimal> fetchBalance() {
        try {
            var response = restClient.get()
                    .uri(API_BASE + accountSid + "/Balance.json")
                    .header("Authorization", basicAuthHeader())
                    .retrieve()
                    .body(MAP_TYPE);
            if (response == null || response.get("balance") == null) {
                log.warn("provider_balance.twilio.unreadable");
                return Optional.empty();
            }
            var currency = String.valueOf(response.get("currency"));
            currencySymbol = US_DOLLAR.equalsIgnoreCase(currency) ? "US$" : currency;
            return Optional.of(new BigDecimal(response.get("balance").toString()));
        } catch (RuntimeException ex) {
            log.warn("provider_balance.twilio.failed reason={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public BigDecimal exhaustedAtOrBelow() {
        return new BigDecimal("0.06");
    }

    @Override
    public BigDecimal minimumTopUp() {
        return BigDecimal.ONE;
    }

    @Override
    public String whatStops() {
        return "SMS de verificação e WhatsApp param — inclusive os avisos ao admin (que passam a ir por e-mail)";
    }

    private String basicAuthHeader() {
        var credentials = accountSid + ":" + authToken;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
