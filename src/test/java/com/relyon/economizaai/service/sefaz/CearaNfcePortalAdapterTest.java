package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CearaNfcePortalAdapterTest {

    // Real Centerbox nota (Fortaleza, 68 items, R$543,46) fetched live from the
    // qrcodev3 api on 2026-10-12 — the nota that unblocked the CE scraper.
    private static final String CHAVE = "23260311497712000346651050000140211138967393";

    private CearaNfcePortalAdapter adapter(String cannedResponse, AtomicInteger postCount) {
        return new CearaNfcePortalAdapter(RestClient.builder(), 1000, 3, 0L, "test-agent") {
            @Override
            protected String postConsult(String jsonBody) {
                postCount.incrementAndGet();
                if (cannedResponse == null) throw new RestClientException("portal down");
                return cannedResponse;
            }
        };
    }

    private String fixture() throws Exception {
        return CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/ce/nfce-real-centerbox.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void parseRealCearaReceipt_extractsAllFields() throws Exception {
        var adapter = adapter("{}", new AtomicInteger());

        var parsed = adapter.parseHtml(fixture(), CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("11497712000346", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().toUpperCase().contains("CENTERBOX"));
        assertEquals(68, parsed.items().size());
        // Parser semantics: totalAmount = "Valor a pagar" (paid); items keep gross
        // printed prices and sum to the subtotal = paid + discount.
        assertEquals(0, new BigDecimal("528.76").compareTo(parsed.totalAmount()));
        assertEquals(0, new BigDecimal("14.70").compareTo(parsed.discountTotal()));
        var itemsSum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("543.46").compareTo(itemsSum));
        var firstItem = parsed.items().get(0);
        assertEquals(1, firstItem.lineNumber());
        assertFalse(firstItem.rawDescription().isBlank());
        assertTrue(firstItem.totalPrice().signum() > 0);
    }

    @Test
    void supportsBareChave_v3ConsultNeedsNoQrSignature() {
        var adapter = adapter("{}", new AtomicInteger());

        assertEquals(Set.of(UnidadeFederativa.CE), adapter.supportedStates());
        assertFalse(adapter.requiresQrSignature());
    }

    @Test
    void fetchHtml_unwrapsDanfeFromJsonResponse() {
        var postCount = new AtomicInteger();
        var adapter = adapter("{\"xml\": \"<div id='tabResult'>danfe</div>\", \"erro\": null}", postCount);

        var html = adapter.fetchHtml(CHAVE);

        assertEquals("<div id='tabResult'>danfe</div>", html);
        assertEquals(1, postCount.get());
    }

    @Test
    void fetchHtml_sendsV3ConsultBodyWithChave() {
        var bodies = new StringBuilder();
        var adapter = new CearaNfcePortalAdapter(RestClient.builder(), 1000, 3, 0L, "test-agent") {
            @Override
            protected String postConsult(String jsonBody) {
                bodies.append(jsonBody);
                return "{\"xml\": \"<div>danfe</div>\"}";
            }
        };

        adapter.fetchHtml("http://nfce.sefaz.ce.gov.br/pages/ShowNFCe.html?p=" + CHAVE + "|2|1|1|deadbeef");

        assertTrue(bodies.toString().contains("\"chave_acesso\":\"" + CHAVE + "\""));
        assertTrue(bodies.toString().contains("\"versao_qrcode\":\"3\""));
        assertTrue(bodies.toString().contains("\"tipo_ambiente\":\"1\""));
    }

    @Test
    void fetchHtml_portalErro_isDeterministic_noRetryNoFallbackSpend() {
        var postCount = new AtomicInteger();
        var adapter = adapter("{\"xml\": null, \"erro\": \"100 - QR Code Inv\\u00e1lido\"}", postCount);

        assertThrows(SefazDeterministicFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertEquals(1, postCount.get(), "portal rejection must not be retried");
    }

    @Test
    void fetchHtml_emptyResponse_retriesThenThrowsRescuableFailure() {
        var postCount = new AtomicInteger();
        var adapter = adapter("{\"xml\": \"\", \"erro\": \"\"}", postCount);

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertEquals(3, postCount.get(), "empty answer is transient — exhaust the retries");
    }

    @Test
    void fetchHtml_httpFailure_retriesThenThrowsRescuableFailure() {
        var postCount = new AtomicInteger();
        var adapter = adapter(null, postCount);

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertEquals(3, postCount.get());
    }

    @Test
    void fetchHtml_nonJsonResponse_isTransient() {
        var postCount = new AtomicInteger();
        var adapter = adapter("<html>maintenance wall</html>", postCount);

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(CHAVE));
        assertEquals(3, postCount.get());
    }
}
