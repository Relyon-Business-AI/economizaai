package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Rio de Janeiro NFC-e (Boutique Popular, Santa Cruz/RJ, 2024-07) captured
 * from a browser AFTER passing RJ's Imperva/Incapsula anti-bot JS wall. The
 * point of this fixture: RJ's portal ({@code consultadfe.fazenda.rj.gov.br})
 * renders the SAME responsive-DANFE layout as RS/PR/SP, so the shared
 * {@link ResponsiveDanfeParser} parses it UNCHANGED. RJ is therefore NOT a
 * parser problem — the only thing blocking our (JS-less) backend is the Imperva
 * challenge. Once a headless-browser bypass lands, RJ can be promoted to a
 * verified adapter with zero parser work; this test guards that the layout is
 * already handled.
 */
class RealRioDeJaneiroFixtureTest {

    private static final String CHAVE = "33240744005617000175650010000000031892682790";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    // RJ has no dedicated adapter — it's served by the experimental generic
    // adapter, which delegates parsing to the shared ResponsiveDanfeParser.
    private final GenericQrPortalAdapter adapter = new GenericQrPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", true, 1, 0L, "gov.br");

    @Test
    void parseRioReceipt_sharedResponsiveParserHandlesRjUnchanged() throws Exception {
        var html = new String(new ClassPathResource("fixtures/sefaz/rj/nfce-real-boutique.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        var parsed = adapter.parseHtml(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("44005617000175", parsed.cnpjEmitente());
        // Merchant's own registered name carries a typo ("POUPULAR") — assert what SEFAZ returns.
        assertTrue(parsed.marketName().contains("BOUTIQUE"), parsed.marketName());
        assertEquals(0, new BigDecimal("65.00").compareTo(parsed.totalAmount()));

        assertEquals(1, parsed.items().size());
        var item = parsed.items().get(0);
        assertTrue(item.rawDescription().contains("Blusa"), item.rawDescription());
        assertEquals(0, BigDecimal.ONE.compareTo(item.quantity()));
        assertEquals(0, new BigDecimal("65.00").compareTo(item.unitPrice()));
        assertEquals(0, new BigDecimal("65.00").compareTo(item.totalPrice()));
    }
}
