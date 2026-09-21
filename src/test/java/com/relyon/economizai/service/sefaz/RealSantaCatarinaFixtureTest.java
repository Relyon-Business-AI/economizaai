package com.relyon.economizai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real SC NFC-e DANFE captured 2026-09-21 from a live scan (C.VALE cooperativa,
 * Santa Catarina — 8 items, R$103,00) after solving the SecurityVerify Turnstile.
 * Consumer CPF was masked before saving. Guards {@link ScNfceDanfeParser} — the
 * parser SC and GO share — against regressions on real SC markup; SC previously
 * had only a synthetic challenge test, and this store parsed 0 items once before
 * succeeding, so a real fixture matters.
 */
class RealSantaCatarinaFixtureTest {

    private static final String CHAVE = "42260982647165000548651140000413341798864714";

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("fixtures/sefaz/sc/" + name)
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void parsesRealScReceiptEndToEnd() throws Exception {
        var parsed = ScNfceDanfeParser.parse(fixture("danfe.html"), CHAVE, "https://sat.sef.sc.gov.br/x");

        assertEquals("82647165000548", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("COOPERATIVA DE PRODUCAO"));
        assertEquals(0, new BigDecimal("103.00").compareTo(parsed.totalAmount()));
        assertEquals(LocalDateTime.of(2026, 9, 18, 9, 24, 15), parsed.issuedAt());
        assertEquals(8, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertEquals("REFRI COCA COLA 2L ORIGINAL PET", first.rawDescription());
        assertNull(first.ean());
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("11.49").compareTo(first.totalPrice()));

        // A weighed item: fractional KG quantity + derived unit price.
        var melao = parsed.items().stream()
                .filter(item -> item.rawDescription().contains("MELAO")).findFirst().orElseThrow();
        assertEquals("KG", melao.unit());
        assertEquals(0, new BigDecimal("2.105").compareTo(melao.quantity()));
        assertEquals(0, new BigDecimal("12.99").compareTo(melao.unitPrice()));
        assertEquals(0, new BigDecimal("27.34").compareTo(melao.totalPrice()));

        var itemSum = parsed.items().stream()
                .map(item -> item.totalPrice()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()));
    }
}
