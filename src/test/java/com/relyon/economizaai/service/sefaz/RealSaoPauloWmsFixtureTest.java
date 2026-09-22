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
 * Real São Paulo NFC-e (WMS Supermercados / Sam's Club, Av. Jabaquara,
 * 2026-07-08) — second SP fixture, locking in two merchant quirks the
 * Mercadao fixture doesn't exercise:
 * <ul>
 *   <li>ALPHANUMERIC internal item codes ("AR062620") in the Código slot —
 *       never stored as EANs even when their digit run reaches 8+;</li>
 *   <li>ERP unit strings with a trailing tax-group digit ("UND9"/"KG9"/"UND8")
 *       — normalized to comparable units (UN/KG) by {@link UnitNormalizer}.</li>
 * </ul>
 */
class RealSaoPauloWmsFixtureTest {

    private static final String CHAVE = "35260793209765069826655020001057921048579171";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS,PR,SP", 5, 0L,
            "svrs.rs.gov.br,sefaz.rs.gov.br,fazenda.pr.gov.br,fazenda.sp.gov.br");

    @Test
    void parseWmsReceipt_normalizesUnitsAndRejectsAlphanumericCodes() throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/sp/nfce-real-wms.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        var parsed = adapter.parseHtml(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("93209765069826", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("WMS SUPERMERCADOS"));
        assertEquals(LocalDateTime.of(2026, 7, 8, 22, 15, 41), parsed.issuedAt());
        assertEquals(0, new BigDecimal("386.84").compareTo(parsed.totalAmount()));
        assertEquals(28, parsed.items().size());

        var toothbrush = parsed.items().get(0);
        assertTrue(toothbrush.rawDescription().contains("ESC.DENTAL ORAL-B"));
        assertNull(toothbrush.ean(), "alphanumeric internal code (AR062620) is not an EAN");
        assertEquals("UN", toothbrush.unit(), "UND9 normalizes to UN");
        assertEquals(0, new BigDecimal("24.90").compareTo(toothbrush.totalPrice()));

        var porkChop = parsed.items().get(5);
        assertTrue(porkChop.rawDescription().contains("BISTECA"));
        assertEquals("KG", porkChop.unit(), "KG9 normalizes to KG");
        assertEquals(0, new BigDecimal("1.256").compareTo(porkChop.quantity()));
        assertEquals(0, new BigDecimal("23.74").compareTo(porkChop.totalPrice()));

        var bag = parsed.items().get(27);
        assertTrue(bag.rawDescription().contains("SACOLA"));
        assertEquals("UN", bag.unit(), "UND8 normalizes to UN");

        assertTrue(parsed.items().stream().allMatch(item -> item.ean() == null),
                "WMS prints only internal codes — no item may carry an EAN");
    }
}
