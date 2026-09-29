package com.relyon.economizaai.service.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Thin wrapper over the Mercado Pago preapproval (assinatura) API. Creation
 * returns the {@code init_point} URL the FE opens for the user to authorize the
 * recurring charge; the webhook flow re-fetches the preapproval to read its
 * authoritative status. Throws {@link MercadoPagoApiException} on any non-2xx
 * or malformed payload — retry/fallback is the caller's concern.
 */
@Slf4j
@Service
public class MercadoPagoClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MercadoPagoProperties properties;

    public MercadoPagoClient(RestClient.Builder builder, MercadoPagoProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(15000);
        this.restClient = builder
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    /** The subset of a preapproval we act on. */
    public record Preapproval(
            String id,
            String status,
            String initPoint,
            String externalReference,
            String payerEmail,
            BigDecimal transactionAmount,
            LocalDateTime nextPaymentDate) {
    }

    public Preapproval createPreapproval(String payerEmail, String externalReference) {
        var body = Map.of(
                "reason", properties.getPlanReason(),
                "external_reference", externalReference,
                "payer_email", payerEmail,
                "back_url", properties.getBackUrl(),
                "auto_recurring", Map.of(
                        "frequency", 1,
                        "frequency_type", "months",
                        "transaction_amount", properties.getPlanAmount(),
                        "currency_id", "BRL"));
        var json = exchange(() -> restClient.post()
                .uri(properties.getApiBaseUrl() + "/preapproval")
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class));
        return parsePreapproval(json);
    }

    public Preapproval fetchPreapproval(String preapprovalId) {
        var json = exchange(() -> restClient.get()
                .uri(properties.getApiBaseUrl() + "/preapproval/" + preapprovalId)
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .retrieve()
                .body(String.class));
        return parsePreapproval(json);
    }

    private String exchange(ApiCall call) {
        try {
            var response = call.execute();
            if (response == null || response.isBlank()) {
                throw new MercadoPagoApiException("empty response");
            }
            return response;
        } catch (RestClientException httpFailure) {
            throw new MercadoPagoApiException(httpFailure.getMessage());
        }
    }

    private Preapproval parsePreapproval(String json) {
        try {
            var root = objectMapper.readTree(json);
            if (root.path("id").isMissingNode() || root.path("id").asText().isBlank()) {
                throw new MercadoPagoApiException("response without preapproval id");
            }
            return new Preapproval(
                    root.path("id").asText(),
                    root.path("status").asText(null),
                    root.path("init_point").asText(null),
                    root.path("external_reference").asText(null),
                    root.path("payer_email").asText(null),
                    amountOf(root.path("auto_recurring").path("transaction_amount")),
                    dateOf(root.path("next_payment_date")));
        } catch (MercadoPagoApiException invalid) {
            throw invalid;
        } catch (Exception parseFailure) {
            throw new MercadoPagoApiException("unparseable response: " + parseFailure.getMessage());
        }
    }

    private BigDecimal amountOf(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private LocalDateTime dateOf(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) return null;
        try {
            return OffsetDateTime.parse(node.asText()).toLocalDateTime();
        } catch (Exception unparseable) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ApiCall {
        String execute();
    }
}
