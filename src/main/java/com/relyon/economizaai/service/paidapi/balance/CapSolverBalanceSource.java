package com.relyon.economizaai.service.paidapi.balance;

import com.relyon.economizaai.config.CaptchaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** CapSolver prepaid balance (US$) via {@code POST /getBalance}. Active only when CapSolver is the solver. */
@Slf4j
@Component
public class CapSolverBalanceSource implements ProviderBalanceSource {

    private static final String BALANCE_URL = "https://api.capsolver.com/getBalance";
    private static final String CAPSOLVER_PROVIDER = "capsolver";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final CaptchaProperties captchaProperties;

    @Autowired
    public CapSolverBalanceSource(RestClient.Builder builder, CaptchaProperties captchaProperties) {
        this(builder.requestFactory(timeoutFactory()).build(), captchaProperties);
    }

    /** Test seam: inject a pre-built (mock-bound) RestClient. */
    CapSolverBalanceSource(RestClient restClient, CaptchaProperties captchaProperties) {
        this.restClient = restClient;
        this.captchaProperties = captchaProperties;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return requestFactory;
    }

    @Override
    public String providerKey() {
        return "CAPSOLVER";
    }

    @Override
    public String displayName() {
        return "CapSolver";
    }

    @Override
    public String currencySymbol() {
        return "US$";
    }

    @Override
    public boolean isConfigured() {
        var apiKey = captchaProperties.getApiKey();
        return CAPSOLVER_PROVIDER.equalsIgnoreCase(captchaProperties.getProvider())
                && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Optional<BigDecimal> fetchBalance() {
        try {
            var response = restClient.post()
                    .uri(BALANCE_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("clientKey", captchaProperties.getApiKey()))
                    .retrieve()
                    .body(MAP_TYPE);
            if (response == null || !(response.get("errorId") instanceof Number errorId) || errorId.intValue() != 0
                    || response.get("balance") == null) {
                log.warn("provider_balance.capsolver.unreadable errorCode={}",
                        response == null ? null : response.get("errorCode"));
                return Optional.empty();
            }
            return Optional.of(new BigDecimal(response.get("balance").toString()));
        } catch (RuntimeException ex) {
            log.warn("provider_balance.capsolver.failed reason={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public BigDecimal exhaustedAtOrBelow() {
        return new BigDecimal("0.01");
    }

    @Override
    public BigDecimal minimumTopUp() {
        return BigDecimal.ONE;
    }

    @Override
    public String whatStops() {
        return "as notas de estados com captcha param (estados sem captcha seguem normais)";
    }
}
