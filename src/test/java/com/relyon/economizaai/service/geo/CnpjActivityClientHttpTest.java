package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.enums.MerchantSegment;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link CnpjActivityClient#classify(String)}'s parse path by
 * subclassing and overriding the {@code fetchWithRetry} HTTP seam with canned
 * BrasilAPI bodies — no network. Covers the primary-CNAE pharmacy/supermarket
 * mappings, secondary-CNAE fallthrough, malformed JSON, and a null body.
 */
class CnpjActivityClientHttpTest {

    private static final String CNPJ = "11111111000111";

    /** A client that returns a fixed body from the fetch seam. */
    private CnpjActivityClient clientReturning(String cannedBody) {
        return new CnpjActivityClient(RestClient.builder(),
                "https://x/api", 1, 3, "agent", true) {
            @Override
            protected String fetchWithRetry(String cnpj) {
                return cannedBody;
            }
        };
    }

    @Test
    void primaryPharmacyCnae_classifiesPharmacy() {
        var body = "{\"cnae_fiscal\":4771701,\"cnaes_secundarios\":[{\"codigo\":4729602}]}";
        assertEquals(MerchantSegment.PHARMACY, clientReturning(body).classify(CNPJ));
    }

    @Test
    void primarySupermarketCnae_classifiesSupermarket() {
        var body = "{\"cnae_fiscal\":4711301,\"cnaes_secundarios\":[]}";
        assertEquals(MerchantSegment.SUPERMARKET, clientReturning(body).classify(CNPJ));
    }

    @Test
    void classifiesByPrimaryCnae_ignoringSecondaries() {
        // Primary is conveniência (food retail); a 4771 pharmacy code is only SECONDARY.
        // The primary wins — matching secondaries falsely tagged Amazon/Cobasi as PHARMACY.
        var body = "{\"cnae_fiscal\":4729602,\"cnaes_secundarios\":[{\"codigo\":4771701}]}";
        assertEquals(MerchantSegment.FOOD_RETAIL, clientReturning(body).classify(CNPJ));
    }

    @Test
    void unrelatedCnae_classifiesOther() {
        // posto de gasolina without a conveniência CNAE — outside every bucket
        var body = "{\"cnae_fiscal\":4731800,\"cnaes_secundarios\":[]}";
        assertEquals(MerchantSegment.OTHER, clientReturning(body).classify(CNPJ));
    }

    @Test
    void restaurantCnae_classifiesFoodService() {
        var body = "{\"cnae_fiscal\":5611201,\"cnaes_secundarios\":[]}";
        assertEquals(MerchantSegment.FOOD_SERVICE, clientReturning(body).classify(CNPJ));
    }

    @Test
    void noCnaesPresent_classifiesUnknown() {
        // valid JSON but no cnae fields at all → empty list → UNKNOWN
        var body = "{\"razao_social\":\"ACME LTDA\"}";
        assertEquals(MerchantSegment.UNKNOWN, clientReturning(body).classify(CNPJ));
    }

    @Test
    void nullSecondaryCodigo_isSkipped_andPrimaryDecides() {
        var body = "{\"cnae_fiscal\":4711301,\"cnaes_secundarios\":[{\"codigo\":null}]}";
        assertEquals(MerchantSegment.SUPERMARKET, clientReturning(body).classify(CNPJ));
    }

    @Test
    void malformedJson_classifiesUnknown() {
        var body = "{not-json";
        assertEquals(MerchantSegment.UNKNOWN, clientReturning(body).classify(CNPJ));
    }

    @Test
    void nullBody_classifiesUnknown() {
        assertEquals(MerchantSegment.UNKNOWN, clientReturning(null).classify(CNPJ));
    }
}
