package com.relyon.economizai.service.analytics.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over the Meta (Facebook/Instagram) Marketing API insights
 * endpoint. Fetches daily per-campaign spend for a date range and returns
 * flattened {@link MetaAdInsight} rows. Follows Meta's {@code paging.next}
 * cursor (bounded) and throws {@link MetaAdsApiException} on any non-2xx or
 * {@code error} payload. Does NOT retry — that's the caller's concern.
 */
@Slf4j
@Service
public class MetaAdsClient {

    /** Hard ceiling on paging cursor hops so a runaway response can't loop forever. */
    private static final int MAX_PAGES = 20;
    private static final String DEFAULT_CURRENCY = "BRL";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetaAdsProperties properties;

    public MetaAdsClient(RestClient.Builder builder, MetaAdsProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(30000);
        this.restClient = builder
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    /**
     * One flattened daily spend row for one campaign. {@code spendDate} is
     * Meta's {@code date_start} (== date_stop under {@code time_increment=1}).
     */
    public record MetaAdInsight(
            String campaignId,
            String campaignName,
            LocalDate spendDate,
            BigDecimal spend,
            String currency,
            long impressions,
            long clicks,
            long reach) {
    }

    /**
     * Current budget/status snapshot for one campaign. Budgets are already in
     * R$ (Meta returns minor units, e.g. cents, which we shift). {@code endsAt}
     * is Meta's {@code stop_time}; null when the campaign is open-ended.
     */
    public record MetaCampaignInfo(
            String campaignId,
            String name,
            String status,
            BigDecimal lifetimeBudget,
            BigDecimal budgetRemaining,
            OffsetDateTime endsAt) {
    }

    /**
     * Fetches the current budget/status of every campaign on the ad account.
     * Single page (accounts have few campaigns); throws {@link MetaAdsApiException}
     * on failure so the caller can decide whether to swallow it.
     */
    public List<MetaCampaignInfo> fetchCampaigns() {
        var url = properties.getGraphBaseUrl()
                + "/" + properties.getApiVersion()
                + "/act_" + properties.getAdAccountId() + "/campaigns"
                + "?fields=id,name,effective_status,daily_budget,lifetime_budget,budget_remaining,stop_time"
                + "&limit=200"
                + "&access_token=" + encode(properties.getToken());
        var json = fetchPage(url);
        var data = json.get("data");
        var campaigns = new ArrayList<MetaCampaignInfo>();
        if (data != null && data.isArray()) {
            for (JsonNode node : data) {
                campaigns.add(toCampaign(node));
            }
        }
        log.info("meta.fetch.campaigns count={}", campaigns.size());
        return campaigns;
    }

    private MetaCampaignInfo toCampaign(JsonNode node) {
        return new MetaCampaignInfo(
                node.path("id").asText(null),
                node.path("name").asText(null),
                node.path("effective_status").asText(null),
                centsToReais(node.path("lifetime_budget").asText(null)),
                centsToReais(node.path("budget_remaining").asText(null)),
                parseOffsetDateTime(node.path("stop_time").asText(null)));
    }

    /** Meta budgets come in the account's minor unit (e.g. "70000" = R$ 700,00). */
    private BigDecimal centsToReais(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim()).movePointLeft(2);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private OffsetDateTime parseOffsetDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // Meta uses "2026-09-21T16:23:07+0000" (offset without a colon).
            return OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Fetches daily per-campaign insights for {@code [since, until]}. Follows
     * paging until exhausted or {@link #MAX_PAGES} reached.
     *
     * @throws MetaAdsApiException on non-2xx, an {@code error} payload, or a
     *                             malformed body.
     */
    public List<MetaAdInsight> fetchDailyCampaignInsights(LocalDate since, LocalDate until) {
        var insights = new ArrayList<MetaAdInsight>();
        var nextUrl = firstPageUrl(since, until);
        var page = 0;
        while (nextUrl != null && page < MAX_PAGES) {
            page++;
            var json = fetchPage(nextUrl);
            insights.addAll(parseData(json));
            nextUrl = nextCursor(json);
        }
        log.info("meta.fetch.done since={} until={} pages={} rows={}", since, until, page, insights.size());
        return insights;
    }

    private String firstPageUrl(LocalDate since, LocalDate until) {
        var timeRange = "{\"since\":\"" + since + "\",\"until\":\"" + until + "\"}";
        return properties.getGraphBaseUrl()
                + "/" + properties.getApiVersion()
                + "/act_" + properties.getAdAccountId() + "/insights"
                + "?level=campaign"
                + "&time_increment=1"
                + "&time_range=" + encode(timeRange)
                + "&fields=campaign_id,campaign_name,spend,impressions,clicks,reach"
                + "&limit=500"
                + "&access_token=" + encode(properties.getToken());
    }

    private JsonNode fetchPage(String url) {
        String body;
        try {
            body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        } catch (RestClientException ex) {
            throw new MetaAdsApiException("Meta insights request failed: " + ex.getMessage(), ex);
        }
        if (body == null || body.isBlank()) {
            throw new MetaAdsApiException("Meta insights returned an empty body");
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new MetaAdsApiException("Meta insights returned a malformed body", ex);
        }
        var error = json.get("error");
        if (error != null && !error.isNull()) {
            var message = error.path("message").asText("unknown");
            throw new MetaAdsApiException("Meta insights returned an error: " + message);
        }
        return json;
    }

    private List<MetaAdInsight> parseData(JsonNode json) {
        var data = json.get("data");
        if (data == null || !data.isArray()) return List.of();
        var rows = new ArrayList<MetaAdInsight>();
        for (JsonNode node : data) {
            rows.add(toInsight(node));
        }
        return rows;
    }

    private MetaAdInsight toInsight(JsonNode node) {
        var currency = node.path("account_currency").asText(DEFAULT_CURRENCY);
        if (currency == null || currency.isBlank()) currency = DEFAULT_CURRENCY;
        return new MetaAdInsight(
                node.path("campaign_id").asText(null),
                node.path("campaign_name").asText(null),
                LocalDate.parse(node.path("date_start").asText()),
                parseMoney(node.path("spend").asText("0")),
                currency,
                parseLong(node.path("impressions").asText("0")),
                parseLong(node.path("clicks").asText("0")),
                parseLong(node.path("reach").asText("0")));
    }

    private BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** Meta returns an absolute next-page URL under {@code paging.next}; absent = last page. */
    private String nextCursor(JsonNode json) {
        var next = json.path("paging").path("next");
        return next.isMissingNode() || next.isNull() || next.asText().isBlank() ? null : next.asText();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
