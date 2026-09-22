package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Mato Grosso do Sul NFC-e (C.VALE, Caarapó, 2026-06-10) — the
 * <b>post-captcha</b> DANFE saved from {@code dfe.ms.gov.br/nfce/consulta}.
 * Confirms MS serves the same responsive layout {@link ResponsiveDanfeParser}
 * already reads (so {@link MsDfePortalAdapter} only needs the captcha step in
 * front of the shared parser). Markup quirks vs RS/PR locked here:
 * <ul>
 *   <li>item description uses {@code span.txtTit} (not {@code txtTit2});</li>
 *   <li>"(Código: …)" carries 7-digit merchant-internal codes → {@code ean} null;</li>
 *   <li>single consolidated IBPT total (no Federal/Estadual split) → both null.</li>
 * </ul>
 */
class RealMsFixtureTest {

    private static final String CHAVE = "50260677863223012709650190004048511190344086";

    @Test
    void parseMsReceipt_extractsAllFields() throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/ms/nfce-real-cvale.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        var parsed = ResponsiveDanfeParser.parse(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("77863223012709", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("C.VALE"));
        assertTrue(parsed.marketAddress().toUpperCase().contains("CAARAPO"));
        assertEquals(LocalDateTime.of(2026, 6, 10, 18, 22, 9), parsed.issuedAt());
        assertEquals(0, new BigDecimal("61.79").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal());
        // MS prints one consolidated IBPT total without the Federal/Estadual split.
        assertNull(parsed.approxTaxFederal());
        assertNull(parsed.approxTaxEstadual());

        assertEquals(9, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertTrue(first.rawDescription().contains("COXINHA DA ASA"));
        assertNull(first.ean(), "código 1102252 is a 7-digit internal code, not an EAN");
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("15.99").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("15.99").compareTo(first.totalPrice()));

        // a weighed item (kg) — quantity carries 3 decimals
        var melao = parsed.items().stream()
                .filter(item -> item.rawDescription().contains("MELAO"))
                .findFirst().orElseThrow();
        assertEquals("KG", melao.unit());
        assertEquals(0, new BigDecimal("10.31").compareTo(melao.totalPrice()));

        var itemSum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()), "9 item totals sum to the printed total");
    }
}
