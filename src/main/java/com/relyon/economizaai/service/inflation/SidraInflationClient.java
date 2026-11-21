package com.relyon.economizaai.service.inflation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the official monthly food inflation from IBGE SIDRA (table 7060, IPCA, variable 63:
 * monthly % change) for Brasil, the metropolitan regions and the municipalities IBGE surveys.
 * Free and without authentication. Best-effort: transient failures are retried, and a call
 * that cannot be completed returns nothing — the next daily run tries again.
 */
@Slf4j
@Service
public class SidraInflationClient {

    static final String NATIONAL_LEVEL = "1";
    private static final String MONTHLY_CHANGE_PATH =
            "/values/t/7060/n1/all/n7/all/n6/all/v/63/p/last%%20%d/c315/%s?formato=json";
    private static final DateTimeFormatter SIDRA_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** One official number: the monthly % change of an item in an area. */
    public record OfficialReading(String areaCode, String areaName, String state, String itemCode, String itemName,
                                  YearMonth month, BigDecimal changePercent) {}

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PriceInflationProperties.Sidra sidra;

    public SidraInflationClient(RestClient.Builder builder, PriceInflationProperties properties) {
        this.sidra = properties.getSidra();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(sidra.getTimeoutMs(), 5000));
        requestFactory.setReadTimeout(sidra.getTimeoutMs());
        this.restClient = builder
                .defaultHeader("User-Agent", sidra.getUserAgent())
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * The last {@code months} published months of every configured item in every area. Empty
     * when IBGE could not be reached or answered something unreadable.
     */
    public List<OfficialReading> fetchMonthlyChanges(int months) {
        var body = fetchWithRetry(monthlyChangeUrl(months));
        if (body == null) return List.of();
        try {
            return parse(body);
        } catch (Exception ex) {
            log.warn("price_inflation.sidra.parse_failed {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    String monthlyChangeUrl(int months) {
        var items = new ArrayList<String>();
        items.add(sidra.getFoodAtHomeItem());
        items.addAll(sidra.getGroupItems());
        var baseUrl = sidra.getBaseUrl().endsWith("/")
                ? sidra.getBaseUrl().substring(0, sidra.getBaseUrl().length() - 1) : sidra.getBaseUrl();
        return baseUrl + MONTHLY_CHANGE_PATH.formatted(months, String.join(",", items));
    }

    /** Raw fetch with retry — a seam so the parse path is testable without the network. */
    protected String fetchWithRetry(String url) {
        var maxAttempts = Math.max(1, sidra.getMaxAttempts());
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                var body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
                if (body != null && !body.isBlank()) return body;
                log.warn("price_inflation.sidra.empty_body attempt={}/{}", attempt, maxAttempts);
            } catch (HttpClientErrorException refused) {
                log.warn("price_inflation.sidra.refused status={}", refused.getStatusCode().value());
                return null;
            } catch (Exception transientFailure) {
                log.warn("price_inflation.sidra.fetch_failed attempt={}/{} {}",
                        attempt, maxAttempts, transientFailure.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * SIDRA answers a flat array whose first row is the header. Rows without a number (IBGE
     * writes "..." or "-" when a value does not exist) are skipped.
     */
    List<OfficialReading> parse(String body) throws Exception {
        var rows = objectMapper.readTree(body);
        var readings = new ArrayList<OfficialReading>();
        if (!rows.isArray()) return readings;
        for (var index = 1; index < rows.size(); index++) {
            reading(rows.get(index)).ifPresent(readings::add);
        }
        return readings;
    }

    private static Optional<OfficialReading> reading(JsonNode row) {
        try {
            var areaCode = row.path("D1C").asText();
            var month = YearMonth.parse(row.path("D3C").asText(), SIDRA_MONTH);
            var changePercent = new BigDecimal(row.path("V").asText().trim());
            return Optional.of(new OfficialReading(areaCode, row.path("D1N").asText(),
                    stateOf(row.path("NC").asText(), areaCode), row.path("D4C").asText(), row.path("D4N").asText(),
                    month, changePercent));
        } catch (IllegalArgumentException | DateTimeParseException | IndexOutOfBoundsException noValue) {
            return Optional.empty();
        }
    }

    /** Every area but Brasil belongs to the state whose IBGE code opens the area code. */
    private static String stateOf(String level, String areaCode) {
        if (NATIONAL_LEVEL.equals(level)) return null;
        return UnidadeFederativa.fromIbgeCode(Integer.parseInt(areaCode.substring(0, 2))).name();
    }
}
