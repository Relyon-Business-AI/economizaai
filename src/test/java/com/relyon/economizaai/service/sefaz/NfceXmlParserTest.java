package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Pernambuco NFC-e captured 2026-09-19 from an organic user scan (the 4 notes
 * that failed for carlabarbosatk). PE 301-redirects http:80 -> https:444 and answers
 * a plain GET with the NFe v4.00 XML (the browser renders it via XSL), so the DANFE
 * HTML parser found nothing. Fixtures under fixtures/sefaz/pe/.
 */
class NfceXmlParserTest {

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("fixtures/sefaz/pe/" + name)
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void parsesFourItemSupermarketReceipt() throws Exception {
        var chave = "26260920534381000287651020001738551001743421";
        var parsed = NfceXmlParser.parse(fixture("pe-novo-israel-4items.xml"), chave, "https://test/source");

        assertEquals(chave, parsed.chaveAcesso());
        assertEquals("20534381000287", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("NOVO ISRAEL"));
        assertTrue(parsed.marketAddress().contains("Abreu e Lima - PE"));
        assertEquals(4, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("24.13")));
        assertNull(parsed.discountTotal());
        assertEquals(2026, parsed.issuedAt().getYear());
        assertEquals(9, parsed.issuedAt().getMonthValue());

        var first = parsed.items().get(0);
        assertEquals("RACAO DOG CHOW KG", first.rawDescription());
        assertNull(first.ean(), "SEM GTIN must not become an EAN");
        assertEquals(0, first.quantity().compareTo(new BigDecimal("0.5100")));
        assertEquals("KG", first.unit());
        assertEquals(0, first.unitPrice().compareTo(new BigDecimal("16.99")));
        assertEquals(0, first.totalPrice().compareTo(new BigDecimal("8.66")));
    }

    @Test
    void parsesThreeItemFastFoodReceipt() throws Exception {
        var parsed = NfceXmlParser.parse(fixture("pe-mcdonalds-3items.xml"),
                "26260942591651264205650010000777891671062850", "https://test/source");

        assertEquals("42591651264205", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("ARCOS DOURADOS"));
        assertEquals(3, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("31.90")));
        assertEquals("UN", parsed.items().get(0).unit());
    }

    @Test
    void parsesSingleItemWithRealGtin() throws Exception {
        var parsed = NfceXmlParser.parse(fixture("pe-lucidario-1item.xml"),
                "26260904481837000150651030000223191103232165", "https://test/source");

        assertEquals(1, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("10.99")));
        var only = parsed.items().get(0);
        assertTrue(only.rawDescription().contains("OVOS CLARAGEMA"));
        assertEquals("7898936459749", only.ean(), "a real 13-digit GTIN must survive");
    }

    @Test
    void parsesWeighedItemReceipt() throws Exception {
        var parsed = NfceXmlParser.parse(fixture("pe-lucidario-2items.xml"),
                "26260904481837000150651030000222091103231035", "https://test/source");

        assertEquals(2, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("15.62")));
        var first = parsed.items().get(0);
        assertEquals("PAO DIVERSOS", first.rawDescription());
        assertEquals(0, first.quantity().compareTo(new BigDecimal("0.9200")));
        assertEquals(0, first.totalPrice().compareTo(new BigDecimal("11.04")));
    }

    @Test
    void looksLikeNfeXml_true_for_xml_false_for_html() {
        assertTrue(NfceXmlParser.looksLikeNfeXml(
                "<?xml version=\"1.0\"?><nfeProc><NFe><infNFe Id=\"NFe26\"></infNFe></NFe></nfeProc>"));
        assertFalse(NfceXmlParser.looksLikeNfeXml("<html><body><table id=\"tabResult\"></table></body></html>"));
        assertFalse(NfceXmlParser.looksLikeNfeXml(null));
        assertFalse(NfceXmlParser.looksLikeNfeXml(""));
    }
}
