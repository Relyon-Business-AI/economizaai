package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Retry classification of the GO adapter — the network round-trip is stubbed. */
class GoiasNfcePortalAdapterTest {

    private static final String CHAVE = "52260712345678000199650010000012341000012345";

    private static GoiasNfcePortalAdapter adapter(AtomicInteger attempts, Supplier<String> roundTrip) {
        return new GoiasNfcePortalAdapter(RestClient.builder(), 1000, 3, 0, "test") {
            @Override
            protected String fetchOnce(String shellUrl, String chave) {
                attempts.incrementAndGet();
                return roundTrip.get();
            }
        };
    }

    @Test
    void fetchHtml_deterministicClientError_propagatesWithoutRetryOrPaidFallback() {
        var attempts = new AtomicInteger();
        var adapter = adapter(attempts, () -> { throw new HttpClientErrorException(HttpStatus.NOT_FOUND); });

        assertThrows(SefazDeterministicFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertEquals(1, attempts.get());
    }

    @Test
    void fetchHtml_blockedClientError_staysRescuable() {
        var attempts = new AtomicInteger();
        var adapter = adapter(attempts, () -> { throw new HttpClientErrorException(HttpStatus.FORBIDDEN); });

        var thrown = assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertFalse(thrown instanceof SefazDeterministicFetchException);
        assertEquals(1, attempts.get());
    }

    @Test
    void fetchHtml_transientFailure_retriesUpToMaxAttempts() {
        var attempts = new AtomicInteger();
        var adapter = adapter(attempts, () -> { throw new ResourceAccessException("timeout"); });

        var thrown = assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertFalse(thrown instanceof SefazDeterministicFetchException);
        assertEquals(3, attempts.get());
    }
}
