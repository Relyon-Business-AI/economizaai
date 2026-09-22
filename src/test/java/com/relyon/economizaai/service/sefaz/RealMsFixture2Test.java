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
 * Real Mato Grosso do Sul NFC-e (C.VALE, 2026-07-01) fetched via the live
 * CapSolver path — chave 50260777863223012709650180004455861342485537.
 * 17 items, R$108,41. Verifies the parser handles this receipt shape correctly.
 */
class RealMsFixture2Test {

    private static final String CHAVE = "50260777863223012709650180004455861342485537";

    @Test
    void parseMsReceipt2_extractsAllFields() throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/ms/nfce-real-cvale2.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        var parsed = ResponsiveDanfeParser.parse(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("77863223012709", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("C.VALE"));
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 30, 5), parsed.issuedAt());
        assertEquals(0, new BigDecimal("108.41").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal());
        assertNull(parsed.approxTaxFederal());
        assertNull(parsed.approxTaxEstadual());

        assertEquals(17, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals("BROCOLI HIBRIDO UN", first.rawDescription());
        assertNull(first.ean(), "código 271648 is a 6-digit internal code, not an EAN");
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("7.99").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("7.99").compareTo(first.totalPrice()));

        var abobora = parsed.items().get(1);
        assertEquals("ABOBORA CABOTIA PICADA KG", abobora.rawDescription());
        assertEquals("KG", abobora.unit());
        assertEquals(0, new BigDecimal("2.82").compareTo(abobora.totalPrice()));

        var itemSum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()),
                "17 item totals must sum to the printed total");
    }
}
