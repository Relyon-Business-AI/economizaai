package com.relyon.economizaai.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Resolve um CEP para coordenada aproximada — alternativa ao GPS para definir a
 * localização (funciona na web, sem pedir permissão). Fonte: BrasilAPI CEP v2,
 * que já devolve lat/lng para muitos CEPs; quando não devolve, cai no geocoder
 * ({@link NominatimGeocoder}) sobre o endereço retornado.
 *
 * <p>É intencionalmente grosseiro (nível de bairro/cidade) — só serve para os
 * recortes "perto de você". Nunca guarda o endereço, só a coordenada.
 */
@Slf4j
@Service
public class CepLocationService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final NominatimGeocoder geocoder;

    public CepLocationService(RestClient.Builder builder,
                              NominatimGeocoder geocoder,
                              @Value("${economizaai.geo.cep.base-url:https://brasilapi.com.br/api/cep/v2}") String baseUrl,
                              @Value("${economizaai.geo.cep.timeout-ms:6000}") int timeoutMs,
                              @Value("${economizaai.geo.user-agent:economizai/0.1 (xandivieira@gmail.com)}") String userAgent) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 5000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.geocoder = geocoder;
    }

    public record CepLocation(BigDecimal latitude, BigDecimal longitude, String label) {}

    /** HTTP seam (BrasilAPI CEP v2) — isolado para os testes substituírem sem rede. */
    protected String fetchCep(String cep) {
        return restClient.get().uri(baseUrl + "/" + cep).retrieve().body(String.class);
    }

    public Optional<CepLocation> resolve(String rawCep) {
        var cep = rawCep == null ? "" : rawCep.replaceAll("\\D", "");
        if (cep.length() != 8) return Optional.empty();

        JsonNode body;
        try {
            var raw = fetchCep(cep);
            if (raw == null || raw.isBlank()) return Optional.empty();
            body = objectMapper.readTree(raw);
        } catch (Exception ex) {
            log.warn("cep.lookup.failed cep={} {}: {}", cep, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }

        var city = text(body, "city");
        var state = text(body, "state");
        var neighborhood = text(body, "neighborhood");
        var label = buildLabel(city, state);

        var fromBrasilApi = coordinatesFrom(body.get("location"));
        if (fromBrasilApi.isPresent()) {
            log.info("cep.lookup.ok cep={} source=brasilapi city={} state={}", cep, city, state);
            return Optional.of(new CepLocation(fromBrasilApi.get()[0], fromBrasilApi.get()[1], label));
        }

        // BrasilAPI sem coordenada → geocodifica o endereço (bairro/cidade/UF).
        var address = buildAddress(neighborhood, city, state);
        if (address == null) return Optional.empty();
        return geocoder.geocode(address)
                .map(result -> {
                    log.info("cep.lookup.ok cep={} source=nominatim city={} state={}", cep, city, state);
                    return new CepLocation(result.latitude(), result.longitude(), label);
                });
    }

    /** BrasilAPI CEP v2: {@code location.coordinates.{latitude,longitude}} — pode vir vazio. */
    private Optional<BigDecimal[]> coordinatesFrom(JsonNode location) {
        if (location == null || location.isNull()) return Optional.empty();
        var coordinates = location.get("coordinates");
        if (coordinates == null || coordinates.isNull()) return Optional.empty();
        var latitude = coordinates.get("latitude");
        var longitude = coordinates.get("longitude");
        if (latitude == null || longitude == null
                || latitude.asText().isBlank() || longitude.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal[]{new BigDecimal(latitude.asText()), new BigDecimal(longitude.asText())});
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private String buildAddress(String neighborhood, String city, String state) {
        if (city == null && state == null) return null;
        var parts = new StringBuilder();
        if (neighborhood != null) parts.append(neighborhood).append(", ");
        if (city != null) parts.append(city).append(", ");
        if (state != null) parts.append(state).append(", ");
        parts.append("Brasil");
        return parts.toString();
    }

    private String buildLabel(String city, String state) {
        if (city != null && state != null) return city + " - " + state;
        if (city != null) return city;
        return state;
    }

    private String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
