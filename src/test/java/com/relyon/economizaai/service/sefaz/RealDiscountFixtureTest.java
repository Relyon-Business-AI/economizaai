package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchor test against a real Zaffari NFC-e that carries a receipt-level
 * discount AND an IBPT-source approximate-tax line. Locks in two behaviors
 * we don't want to regress:
 *
 * <ul>
 *   <li>Item prices are kept <b>gross-as-printed</b> — we no longer distribute
 *       the discount across them (that distorted the per-item price index).
 *       The R$ 5,84 discount is tracked at the receipt level as
 *       {@code discountTotal}, and the items keep summing to the printed
 *       subtotal (R$ 112,41), not to "valor a pagar".</li>
 *   <li>{@code parseApproxTax} extracts the IBPT line as
 *       (federal=15.13, estadual=13.73). This is what powers the
 *       "carga tributária aproximada" UX.</li>
 * </ul>
 */
class RealDiscountFixtureTest {

    private static final String CHAVE = "43260593015006000709651090005672911845634822";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");

    @Test
    void parseDiscountReceipt_keepsGrossItemsAndTracksDiscount() throws Exception {
        var html = new String(new ClassPathResource("fixtures/sefaz/rs/nfce-real-discount.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var parsed = adapter.parseHtml(html, CHAVE, "https://test/source");

        assertEquals("93015006000709", parsed.cnpjEmitente());
        assertNotNull(parsed.marketName());

        // grand total = "Valor a pagar R$ 106,57" — what was actually paid
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("106.57")));
        assertEquals(6, parsed.items().size());

        // receipt-level discount is tracked, NOT distributed across items
        assertEquals(0, parsed.discountTotal().compareTo(new BigDecimal("5.84")));

        // items keep their gross printed prices → they sum to the subtotal
        // (112,41), which is exactly totalAmount + discountTotal
        var sum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("112.41")),
                "items must keep gross printed prices (no discount distribution)");
        assertEquals(0, sum.compareTo(parsed.totalAmount().add(parsed.discountTotal())),
                "subtotal must equal paid total + tracked discount");

        // IBPT-source approximate tax — surfaces "Trib aprox R$ 15,13 Federal, R$ 13,73 Estadual"
        assertEquals(0, parsed.approxTaxFederal().compareTo(new BigDecimal("15.13")));
        assertEquals(0, parsed.approxTaxEstadual().compareTo(new BigDecimal("13.73")));

        // CPF must have been redacted before this fixture was committed
        assertTrue(parsed.rawHtml().contains("000.000.000-00"));
        assertTrue(!parsed.rawHtml().matches("(?s).*041\\.603\\.690-22.*"));
    }
}
