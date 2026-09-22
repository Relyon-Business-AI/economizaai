package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the SEFAZ-RS parser against a second real chain (Bistek). Confirms
 * the same selector set Zaffari uses works for another chain on the same
 * XSLT template — i.e., we're parsing the SEFAZ template, not Zaffari.
 */
class RealBistekFixtureTest {

    private static final String CHAVE = "43260583261420003255656140000288561445164522";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");

    @Test
    void parseRealBistekReceipt() throws Exception {
        var html = new String(new ClassPathResource("fixtures/sefaz/rs/nfce-new-market.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var sanitized = CpfMasker.strip(html);
        var parsed = adapter.parseHtml(sanitized, CHAVE, "https://test/source");

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("83261420003255", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("BISTEK"),
                "expected market name to contain BISTEK, got: " + parsed.marketName());
        assertTrue(parsed.marketAddress().contains("CAVALHADA")
                        && parsed.marketAddress().contains("PORTO ALEGRE"),
                "address must include neighborhood + city, got: " + parsed.marketAddress());
        assertEquals(2026, parsed.issuedAt().getYear());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("119.33")));
        assertEquals(11, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertEquals("CHOCOLATE DIVINE 70G AO LEITE SGLUT", first.rawDescription());
        assertEquals("UN", first.unit());
        assertEquals(0, first.quantity().compareTo(BigDecimal.ONE));
        assertEquals(0, first.unitPrice().compareTo(new BigDecimal("8.99")));
        assertEquals(0, first.totalPrice().compareTo(new BigDecimal("8.99")));
        assertEquals("7899516211450", first.ean());

        // Last item: 2x agua mineral — verify quantity > 1 case
        var water = parsed.items().get(10);
        assertEquals("AGUA MINERAL DA PEDRA 500ml CGAS", water.rawDescription());
        assertEquals(0, water.quantity().compareTo(new BigDecimal("2")));
        assertEquals(0, water.unitPrice().compareTo(new BigDecimal("1.39")));
        assertEquals(0, water.totalPrice().compareTo(new BigDecimal("2.78")));

        // no "Descontos R$" line on this receipt → discountTotal is null
        assertNull(parsed.discountTotal());

        var sumOfItems = parsed.items().stream()
                .map(i -> i.totalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sumOfItems.compareTo(parsed.totalAmount()),
                "items total should equal grand total (no discount on this receipt)");
    }
}
