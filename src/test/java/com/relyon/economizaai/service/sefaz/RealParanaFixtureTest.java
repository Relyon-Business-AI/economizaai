package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Paraná NFC-e (RaiaDrogasil, Curitiba, 2026-03-26) served by PR's own
 * portal ({@code www.fazenda.pr.gov.br/nfce/qrcode}). PR does NOT delegate to
 * SVRS, but its portal renders the SAME responsive DANFE layout (identical CSS
 * selectors), so the shared adapter parses it unchanged. Locks in:
 * <ul>
 *   <li>market name / CNPJ / address / issuedAt extraction on PR markup;</li>
 *   <li>all 3 items with merchant-internal item codes (NOT EANs — 6-7 digits,
 *       below the EAN floor, so {@code ean} stays null and matching falls back
 *       to description);</li>
 *   <li>total from "Valor a pagar" (txtMax), not "Qtd. total de itens";</li>
 *   <li>PR's single-total tax line (no Federal/Estadual split) → both null.</li>
 * </ul>
 */
class RealParanaFixtureTest {

    private static final String CHAVE = "41260361585865261893650030000564031777660148";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS,PR", 5, 0L,
            "svrs.rs.gov.br,sefaz.rs.gov.br,fazenda.pr.gov.br");

    @Test
    void parseParanaReceipt_extractsAllFields() throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/pr/nfce-real-raiadrogasil.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        var parsed = adapter.parseHtml(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("61585865261893", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("RAIADROGASIL"));
        assertTrue(parsed.marketAddress().contains("CURITIBA"));
        assertEquals(LocalDateTime.of(2026, 3, 26, 15, 50, 47), parsed.issuedAt());
        assertEquals(0, new BigDecimal("28.97").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal(), "no discount line on this receipt");
        // PR prints one consolidated tax total (Lei 12.741) without the
        // Federal/Estadual split the IBPT regex needs — stays null by design.
        assertNull(parsed.approxTaxFederal());
        assertNull(parsed.approxTaxEstadual());

        assertEquals(3, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertTrue(first.rawDescription().contains("PAPIN"));
        assertNull(first.ean(), "merchant-internal code (1234772) is not an EAN");
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("9.99").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("9.99").compareTo(first.totalPrice()));

        var itemSum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()));
    }

    @Test
    void resolveUrl_acceptsTheRealParanaQrUrl() {
        var url = "https://www.fazenda.pr.gov.br/nfce/qrcode?p=" + CHAVE + "|3|1";
        assertEquals(url, adapter.resolveUrl(url));
    }

    @Test
    void resolveUrl_bareChaveFallsBackToParanaPortalForUf41() {
        var resolved = adapter.resolveUrl(CHAVE);
        assertTrue(resolved.startsWith("https://www.fazenda.pr.gov.br/nfce/qrcode?p="));
        assertTrue(resolved.contains(CHAVE));
    }
}
