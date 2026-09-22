package com.relyon.economizaai.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Free geocoder backed by OpenStreetMap Nominatim. Returns a single best-match
 * lat/lng for an address, or empty when nothing is found / the call fails.
 *
 * <p>Nominatim usage policy:
 * <ul>
 *   <li>Max 1 request/second per IP — caller must throttle.</li>
 *   <li>Must set a meaningful User-Agent identifying the application.</li>
 *   <li>Bulk geocoding (&gt; few requests/min) requires self-hosting or a
 *       commercial alternative. We're well below that ceiling for V1.</li>
 * </ul>
 */
@Slf4j
@Service
public class NominatimGeocoder {

    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NominatimGeocoder(RestClient.Builder builder,
                             @Value("${economizaai.geo.user-agent:economizai/0.1 (xandivieira@gmail.com)}") String userAgent,
                             @Value("${economizaai.geo.timeout-ms:8000}") int timeoutMs) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    public Optional<GeocodeResult> geocode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        var uri = UriComponentsBuilder.fromUriString(NOMINATIM_URL)
                .queryParam("q", address.trim())
                .queryParam("countrycodes", "br")
                .queryParam("format", "json")
                .queryParam("addressdetails", 1)
                .queryParam("limit", 1)
                .build().toUri();
        log.info("geocode.request address='{}'", address);
        try {
            var body = restClient.get().uri(uri).retrieve().body(String.class);
            if (body == null || body.isBlank() || body.equals("[]")) {
                log.info("geocode.empty address='{}'", address);
                return Optional.empty();
            }
            var json = objectMapper.readTree(body);
            if (!json.isArray() || json.size() == 0) return Optional.empty();
            JsonNode first = json.get(0);
            var lat = new BigDecimal(first.get("lat").asText());
            var lon = new BigDecimal(first.get("lon").asText());
            var addr = first.get("address");
            var city = extractCity(addr);
            var state = extractStateAbbreviation(addr);
            log.info("geocode.ok address='{}' lat={} lon={} city={} state={}",
                    address, lat, lon, city, state);
            return Optional.of(new GeocodeResult(lat, lon, city, state));
        } catch (Exception ex) {
            log.warn("geocode.failed address='{}' {}: {}", address, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private String extractCity(JsonNode addr) {
        if (addr == null) return null;
        // Nominatim returns one of these depending on locality classification
        for (var key : new String[]{"city", "town", "municipality", "village", "city_district"}) {
            var node = addr.get(key);
            if (node != null && !node.asText().isBlank()) return node.asText();
        }
        return null;
    }

    private String extractStateAbbreviation(JsonNode addr) {
        if (addr == null) return null;
        var stateCode = addr.get("ISO3166-2-lvl4");
        if (stateCode != null && stateCode.asText().startsWith("BR-")) {
            return stateCode.asText().substring(3); // "BR-RS" → "RS"
        }
        var stateName = addr.get("state");
        if (stateName != null) return BRAZIL_STATE_BY_NAME.getOrDefault(stateName.asText(), null);
        return null;
    }

    private static final Map<String, String> BRAZIL_STATE_BY_NAME = Map.ofEntries(
            Map.entry("Acre", "AC"), Map.entry("Alagoas", "AL"),
            Map.entry("Amapá", "AP"), Map.entry("Amazonas", "AM"),
            Map.entry("Bahia", "BA"), Map.entry("Ceará", "CE"),
            Map.entry("Distrito Federal", "DF"), Map.entry("Espírito Santo", "ES"),
            Map.entry("Goiás", "GO"), Map.entry("Maranhão", "MA"),
            Map.entry("Mato Grosso", "MT"), Map.entry("Mato Grosso do Sul", "MS"),
            Map.entry("Minas Gerais", "MG"), Map.entry("Pará", "PA"),
            Map.entry("Paraíba", "PB"), Map.entry("Paraná", "PR"),
            Map.entry("Pernambuco", "PE"), Map.entry("Piauí", "PI"),
            Map.entry("Rio de Janeiro", "RJ"), Map.entry("Rio Grande do Norte", "RN"),
            Map.entry("Rio Grande do Sul", "RS"), Map.entry("Rondônia", "RO"),
            Map.entry("Roraima", "RR"), Map.entry("Santa Catarina", "SC"),
            Map.entry("São Paulo", "SP"), Map.entry("Sergipe", "SE"),
            Map.entry("Tocantins", "TO")
    );

    public record GeocodeResult(BigDecimal latitude, BigDecimal longitude, String city, String state) {}
}
