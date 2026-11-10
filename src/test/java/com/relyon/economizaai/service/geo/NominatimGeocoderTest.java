package com.relyon.economizaai.service.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NominatimGeocoderTest {

    private NominatimGeocoder geocoder;

    @BeforeEach
    void setUp() {
        // 1ms timeout so the live Nominatim call fails fast and we never hit the
        // network in CI — we only care about URI building, not the HTTP round-trip.
        geocoder = new NominatimGeocoder(RestClient.builder(), "test-agent", 1);
    }

    @Test
    void geocode_addressWithSpacesAndCommas_doesNotThrow_returnsEmptyOnFailure() {
        // Regression: a raw address with spaces previously threw
        // IllegalArgumentException ("Invalid character ' ' for QUERY_PARAM")
        // at URI-build time (before the try/catch), escaping geocode() entirely
        // and crashing the scheduled geocodePending job. The fix (build() instead
        // of build(true)) percent-encodes the query, so building never throws and
        // any transport failure is swallowed into Optional.empty().
        var address = "AV JUCA BATISTA , 1305 , , CAVALHADA , PORTO ALEGRE , RS, Brasil";

        assertThatCode(() -> {
            var result = geocoder.geocode(address);
            assertThat(result).isEmpty(); // empty because the 1ms call fails, NOT because it threw
        }).doesNotThrowAnyException();
    }

    @Test
    void geocode_blankAddress_returnsEmpty() {
        assertThat(geocoder.geocode("  ")).isEmpty();
    }

    @Test
    void geocode_tooManyRequests_flagsRateLimit() {
        var startedAt = Instant.now();
        var rateLimited = geocoderThrowing(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too many requests", HttpHeaders.EMPTY, new byte[0], null));

        assertThat(rateLimited.geocode("Rua X, Porto Alegre, RS")).isEmpty();
        assertThat(rateLimited.rateLimitedSince(startedAt)).isTrue();
    }

    @Test
    void geocode_ordinaryMiss_isNotARateLimit() {
        var startedAt = Instant.now();

        assertThat(geocoderReturning("[]").geocode("Rua X, Porto Alegre, RS")).isEmpty();
        assertThat(geocoder.rateLimitedSince(startedAt)).isFalse();
    }

    @Test
    void geocode_nullAddress_returnsEmpty() {
        assertThat(geocoder.geocode(null)).isEmpty();
    }

    @Test
    void geocode_success_parsesLatLonCityAndIsoState() {
        var body = """
                [{"lat":"-30.0500","lon":"-51.2200",
                  "address":{"city":"Porto Alegre","ISO3166-2-lvl4":"BR-RS"}}]
                """;
        var mockedGeocoder = geocoderReturning(body);

        var result = mockedGeocoder.geocode("Av Brasil, Porto Alegre");

        assertThat(result).isPresent();
        var hit = result.get();
        assertThat(hit.latitude()).isEqualByComparingTo("-30.05");
        assertThat(hit.longitude()).isEqualByComparingTo("-51.22");
        assertThat(hit.city()).isEqualTo("Porto Alegre");
        assertThat(hit.state()).isEqualTo("RS");
    }

    @Test
    void geocode_success_fallsBackToTownAndStateNameMapping() {
        var body = """
                [{"lat":"-23.5","lon":"-46.6",
                  "address":{"town":"Campinas","state":"São Paulo"}}]
                """;
        var mockedGeocoder = geocoderReturning(body);

        var result = mockedGeocoder.geocode("Campinas");

        assertThat(result).isPresent();
        assertThat(result.get().city()).isEqualTo("Campinas");
        assertThat(result.get().state()).isEqualTo("SP");
    }

    @Test
    void geocode_success_unknownStateNameYieldsNullState() {
        var body = """
                [{"lat":"-1.0","lon":"-2.0",
                  "address":{"municipality":"Somewhere","state":"Nowhereland"}}]
                """;
        var mockedGeocoder = geocoderReturning(body);

        var result = mockedGeocoder.geocode("Somewhere");

        assertThat(result).isPresent();
        assertThat(result.get().city()).isEqualTo("Somewhere");
        assertThat(result.get().state()).isNull();
    }

    @Test
    void geocode_success_noAddressNode_yieldsNullCityAndState() {
        var body = """
                [{"lat":"-1.0","lon":"-2.0"}]
                """;
        var mockedGeocoder = geocoderReturning(body);

        var result = mockedGeocoder.geocode("No address details");

        assertThat(result).isPresent();
        assertThat(result.get().city()).isNull();
        assertThat(result.get().state()).isNull();
    }

    @Test
    void geocode_cityCentroidResult_isRejectedAsMiss() {
        // Nominatim falls back to the Porto Alegre centroid when it can't resolve the street.
        // Storing it would collapse every PoA market onto one coordinate (~0 km distances),
        // so we must treat it as a miss (no coords) rather than a hit.
        var centroid = """
                [{"lat":"-30.0330","lon":"-51.2300","addresstype":"city","class":"place",
                  "type":"city","place_rank":16,
                  "address":{"city":"Porto Alegre","ISO3166-2-lvl4":"BR-RS"}}]
                """;
        var mockedGeocoder = geocoderReturning(centroid);

        assertThat(mockedGeocoder.geocode("Rua Inexistente 999, Porto Alegre, RS")).isEmpty();
    }

    @Test
    void geocode_administrativeBoundaryResult_isRejectedAsMiss() {
        var boundary = """
                [{"lat":"-30.0","lon":"-51.0","addresstype":"administrative","class":"boundary",
                  "type":"administrative","place_rank":12,
                  "address":{"municipality":"Porto Alegre","ISO3166-2-lvl4":"BR-RS"}}]
                """;
        var mockedGeocoder = geocoderReturning(boundary);

        assertThat(mockedGeocoder.geocode("Porto Alegre, RS")).isEmpty();
    }

    @Test
    void geocode_coarsePlaceRank_isRejectedEvenWithoutAddressType() {
        // place_rank alone (coarser than street level, 26) marks a non-precise match.
        var coarse = """
                [{"lat":"-23.5","lon":"-46.6","place_rank":20,
                  "address":{"city":"São Paulo","ISO3166-2-lvl4":"BR-SP"}}]
                """;
        var mockedGeocoder = geocoderReturning(coarse);

        assertThat(mockedGeocoder.geocode("Bairro X, São Paulo")).isEmpty();
    }

    @Test
    void geocode_preciseStreetAddress_isAccepted() {
        var street = """
                [{"lat":"-30.0500","lon":"-51.2200","addresstype":"road","class":"highway",
                  "type":"residential","place_rank":26,
                  "address":{"road":"Av Juca Batista","city":"Porto Alegre","ISO3166-2-lvl4":"BR-RS"}}]
                """;
        var mockedGeocoder = geocoderReturning(street);

        var result = mockedGeocoder.geocode("Av Juca Batista 1305, Porto Alegre, RS");

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualByComparingTo("-30.05");
        assertThat(result.get().state()).isEqualTo("RS");
    }

    @Test
    void geocode_preciseBuildingResult_isAccepted() {
        var building = """
                [{"lat":"-30.06","lon":"-51.21","addresstype":"shop","class":"shop",
                  "type":"supermarket","place_rank":30,
                  "address":{"road":"Rua Y","city":"Porto Alegre","ISO3166-2-lvl4":"BR-RS"}}]
                """;
        var mockedGeocoder = geocoderReturning(building);

        assertThat(mockedGeocoder.geocode("Supermercado Y, Porto Alegre")).isPresent();
    }

    @Test
    void geocode_resultWithoutPrecisionFields_isAccepted() {
        // Older fixtures / minimal responses carry neither addresstype nor place_rank;
        // default to accepting so the existing success-path tests keep passing.
        var minimal = """
                [{"lat":"-1.0","lon":"-2.0","address":{"city":"Somewhere","ISO3166-2-lvl4":"BR-SP"}}]
                """;
        var mockedGeocoder = geocoderReturning(minimal);

        assertThat(mockedGeocoder.geocode("Somewhere")).isPresent();
    }

    @Test
    void geocode_emptyJsonArrayBody_returnsEmpty() {
        var mockedGeocoder = geocoderReturning("[]");

        assertThat(mockedGeocoder.geocode("anything")).isEmpty();
    }

    @Test
    void geocode_nullBody_returnsEmpty() {
        var mockedGeocoder = geocoderReturning(null);

        assertThat(mockedGeocoder.geocode("anything")).isEmpty();
    }

    @Test
    void geocode_nonArrayJsonBody_returnsEmpty() {
        var mockedGeocoder = geocoderReturning("{\"error\":\"nope\"}");

        assertThat(mockedGeocoder.geocode("anything")).isEmpty();
    }

    @Test
    void geocode_malformedJsonBody_returnsEmptyAndSwallowsException() {
        var mockedGeocoder = geocoderReturning("this is not json");

        assertThat(mockedGeocoder.geocode("anything")).isEmpty();
    }

    @Test
    void geocode_restClientThrows_returnsEmpty() {
        var mockedGeocoder = geocoderThrowing(new RuntimeException("boom"));

        assertThat(mockedGeocoder.geocode("anything")).isEmpty();
    }

    /**
     * Builds a NominatimGeocoder whose internal RestClient is fully mocked to
     * return the given body for any GET, so we exercise parsing without the
     * network. The constructor invokes builder.defaultHeader/requestFactory/build,
     * so those are stubbed to hand back our mocked client.
     */
    private NominatimGeocoder geocoderReturning(String body) {
        var responseSpec = mock(RestClient.ResponseSpec.class);
        when(responseSpec.body(String.class)).thenReturn(body);
        return geocoderWith(responseSpec);
    }

    private NominatimGeocoder geocoderThrowing(RuntimeException failure) {
        var responseSpec = mock(RestClient.ResponseSpec.class);
        when(responseSpec.body(String.class)).thenThrow(failure);
        return geocoderWith(responseSpec);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private NominatimGeocoder geocoderWith(RestClient.ResponseSpec responseSpec) {
        var builder = mock(RestClient.Builder.class);
        var restClient = mock(RestClient.class);
        var requestHeadersUriSpec = mock(RestClient.RequestHeadersUriSpec.class);

        lenient().when(builder.defaultHeader(anyString(), any(String[].class))).thenReturn(builder);
        lenient().when(builder.requestFactory(any())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        lenient().when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);

        return new NominatimGeocoder(builder, "test-agent", 8000);
    }
}
