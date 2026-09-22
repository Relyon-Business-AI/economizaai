package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealZaffariFixtureTest {

    private static final String CHAVE = "43260493015006005182651130003394021410514546";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");

    @Test
    void parseRealZaffariReceipt() throws Exception {
        var html = new String(new ClassPathResource("fixtures/sefaz/rs/nfce-real-zaffari.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var sanitized = CpfMasker.strip(html);
        var parsed = adapter.parseHtml(sanitized, CHAVE, "https://test/source");

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("93015006005182", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("ZAFFARI"));
        assertTrue(parsed.marketAddress().contains("JUCA BATISTA"),
                "address must be the street, not the CNPJ line");
        assertEquals(2026, parsed.issuedAt().getYear());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("298.22")),
                "grand total should be 298.22, not the item count (15)");
        assertEquals(15, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertEquals("FILE CX/SC FGO NAT VD IQF 1KG", first.rawDescription());
        assertEquals("UN", first.unit(), "unit should be UN, not UNVL");
        assertEquals(0, first.quantity().compareTo(new BigDecimal("3")));
        assertEquals(0, first.unitPrice().compareTo(new BigDecimal("22.9")));
        assertEquals(0, first.totalPrice().compareTo(new BigDecimal("68.70")));
        assertTrue(first.ean().endsWith("1108429"), "EAN should be normalized");
    }
}
