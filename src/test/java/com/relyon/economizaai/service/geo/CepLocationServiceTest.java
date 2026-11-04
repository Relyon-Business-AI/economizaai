package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.service.geo.NominatimGeocoder.GeocodeResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercita o parse do CEP com corpos canônicos da BrasilAPI (sem rede), via seam
 * {@code fetchCep}. Cobre: coordenada direta da BrasilAPI, fallback pro geocoder
 * quando não há coordenada, CEP malformado e corpo nulo.
 */
class CepLocationServiceTest {

    private CepLocationService serviceReturning(String cannedBody, NominatimGeocoder geocoder) {
        return new CepLocationService(RestClient.builder(), geocoder, "https://x/cep", 1000, "agent") {
            @Override
            protected String fetchCep(String cep) {
                return cannedBody;
            }
        };
    }

    @Test
    void usesBrasilApiCoordinatesWhenPresent() {
        var geocoder = mock(NominatimGeocoder.class);
        var body = "{\"city\":\"Florianópolis\",\"state\":\"SC\",\"neighborhood\":\"Centro\","
                + "\"location\":{\"type\":\"Point\",\"coordinates\":{\"longitude\":\"-48.55\",\"latitude\":\"-27.59\"}}}";

        var result = serviceReturning(body, geocoder).resolve("88010-000");

        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("-27.59").compareTo(result.get().latitude()));
        assertEquals(0, new BigDecimal("-48.55").compareTo(result.get().longitude()));
        assertEquals("Florianópolis - SC", result.get().label());
        verifyNoInteractions(geocoder); // não precisou do geocoder
    }

    @Test
    void fallsBackToGeocoderWhenNoCoordinates() {
        var geocoder = mock(NominatimGeocoder.class);
        when(geocoder.geocode(any())).thenReturn(Optional.of(
                new GeocodeResult(new BigDecimal("-30.03"), new BigDecimal("-51.23"), "Porto Alegre", "RS")));
        var body = "{\"city\":\"Porto Alegre\",\"state\":\"RS\",\"neighborhood\":\"Centro\",\"location\":{\"coordinates\":{}}}";

        var result = serviceReturning(body, geocoder).resolve("90010000");

        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("-30.03").compareTo(result.get().latitude()));
        assertEquals("Porto Alegre - RS", result.get().label());
    }

    @Test
    void malformedCep_isEmpty() {
        var geocoder = mock(NominatimGeocoder.class);
        assertTrue(serviceReturning("{}", geocoder).resolve("123").isEmpty());
        verifyNoInteractions(geocoder);
    }

    @Test
    void nullBody_isEmpty() {
        var geocoder = mock(NominatimGeocoder.class);
        assertTrue(serviceReturning(null, geocoder).resolve("88010000").isEmpty());
    }
}
