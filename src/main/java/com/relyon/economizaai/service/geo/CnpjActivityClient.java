package com.relyon.economizaai.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.enums.MerchantSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks up a CNPJ's business segment from its CNAE (economic activity) via the
 * free, no-auth BrasilAPI registry. Best-effort and resilient: transient
 * failures are retried a few times, and any unrecoverable failure returns
 * {@link MerchantSegment#UNKNOWN} rather than throwing — classification is an
 * enrichment, never a hard requirement on the ingestion flow.
 *
 * <p>CNAE → segment: {@code 4771*} = pharmacy, {@code 4711*}/{@code 4712*} =
 * supermarket, {@code 4721*}-{@code 4724*}/{@code 4729*} = food retail
 * (padarias, açougues, bebidas, hortifrúti, conveniência), {@code 56*} = food
 * service (restaurantes/bares — rejected by the support gate). A supported
 * retail CNAE anywhere in the list (primary or secondary) wins, so a posto de
 * gasolina whose CNPJ lists the conveniência as a secondary activity counts.
 */
@Slf4j
@Service
public class CnpjActivityClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final int maxAttempts;
    private final boolean enabled;

    public CnpjActivityClient(RestClient.Builder builder,
                              @Value("${economizaai.merchant.classify.base-url:https://brasilapi.com.br/api/cnpj/v1}") String baseUrl,
                              @Value("${economizaai.merchant.classify.timeout-ms:8000}") int timeoutMs,
                              @Value("${economizaai.merchant.classify.max-attempts:3}") int maxAttempts,
                              @Value("${economizaai.merchant.classify.user-agent:economizai}") String userAgent,
                              @Value("${economizaai.merchant.classify.enabled:true}") boolean enabled) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 5000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Classifies the CNPJ's segment. Returns UNKNOWN on disabled, bad input, or
     * after exhausting retries — callers treat UNKNOWN as "couldn't verify, carry
     * on without business-type context".
     */
    public MerchantSegment classify(String cnpj) {
        return lookup(cnpj).segment();
    }

    /**
     * Full registry lookup: business segment (from CNAE) + the official IBGE
     * municipality code. Same best-effort semantics as {@link #classify} —
     * failures yield (UNKNOWN, null), never an exception.
     */
    public CnpjLookup lookup(String cnpj) {
        if (!enabled || cnpj == null || cnpj.isBlank()) return CnpjLookup.empty();
        var body = fetchWithRetry(cnpj);
        if (body == null) return CnpjLookup.empty();
        try {
            var json = objectMapper.readTree(body);
            var cnaes = parseCnaes(json);
            return new CnpjLookup(segmentFromCnae(cnaes), parseIbgeCityCode(json), cnaes);
        } catch (Exception ex) {
            log.warn("merchant.classify.parse_failed cnpj={} {}: {}", cnpj, ex.getClass().getSimpleName(), ex.getMessage());
            return CnpjLookup.empty();
        }
    }

    public record CnpjLookup(MerchantSegment segment, String ibgeCityCode, List<String> cnaeCodes) {
        static CnpjLookup empty() {
            return new CnpjLookup(MerchantSegment.UNKNOWN, null, List.of());
        }
    }

    /** Raw fetch with retry — isolated as a seam so classify()'s parse path is unit-testable. */
    protected String fetchWithRetry(String cnpj) {
        var url = baseUrl + "/" + cnpj;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.get().uri(url).retrieve().body(String.class);
            } catch (Exception ex) {
                log.warn("merchant.classify.fetch_failed attempt={}/{} cnpj={} {}",
                        attempt, maxAttempts, cnpj, ex.getClass().getSimpleName());
            }
        }
        return null;
    }

    private List<String> parseCnaes(JsonNode json) {
        var codes = new ArrayList<String>();
        var primary = json.get("cnae_fiscal");
        if (primary != null && !primary.isNull()) codes.add(primary.asText());
        var secundarios = json.get("cnaes_secundarios");
        if (secundarios != null && secundarios.isArray()) {
            for (JsonNode node : secundarios) {
                var code = node.get("codigo");
                if (code != null && !code.isNull()) codes.add(code.asText());
            }
        }
        return codes;
    }

    /** BrasilAPI returns the municipality as `codigo_municipio_ibge` (7-digit number). */
    private String parseIbgeCityCode(JsonNode json) {
        var code = json.get("codigo_municipio_ibge");
        if (code == null || code.isNull()) return null;
        var digits = code.asText().replaceAll("\\D", "");
        return digits.length() == 7 ? digits : null;
    }

    private static final List<String> FOOD_RETAIL_PREFIXES =
            List.of("4721", "4722", "4723", "4724", "4729");

    /**
     * Pure mapping (CNAE prefix → segment), extracted for testability. Classifies by
     * the merchant's PRIMARY (first) CNAE — its registered main activity. {@link #parseCnaes}
     * puts {@code cnae_fiscal} first, followed by the secondaries.
     *
     * <p>Was {@code anyMatch} over the whole list ("any supported CNAE wins"), but that
     * produced false positives: Amazon, Cobasi and pet shops list a pharmacy code (4771*)
     * as a SECONDARY activity and were wrongly tagged PHARMACY — putting e-commerce/pet
     * spend into the physical grocery index. The primary CNAE is the honest signal.
     * Trade-off: a mixed CNPJ (posto + conveniência) now follows its primary (gas → OTHER)
     * instead of its secondary grocery code — accepted, false positives are worse here.
     */
    static MerchantSegment segmentFromCnae(List<String> cnaes) {
        if (cnaes.isEmpty()) return MerchantSegment.UNKNOWN;
        var primary = cnaes.get(0);
        if (primary.startsWith("4771")) return MerchantSegment.PHARMACY;
        if (primary.startsWith("4711") || primary.startsWith("4712")) return MerchantSegment.SUPERMARKET;
        if (isFoodRetailCnae(primary)) return MerchantSegment.FOOD_RETAIL;
        if (primary.startsWith("56")) return MerchantSegment.FOOD_SERVICE;
        return MerchantSegment.OTHER;
    }

    private static boolean isFoodRetailCnae(String code) {
        return FOOD_RETAIL_PREFIXES.stream().anyMatch(code::startsWith);
    }
}
