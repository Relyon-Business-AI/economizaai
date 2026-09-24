package com.relyon.economizaai.service.canonicalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DescriptionNormalizerTest {

    @Test
    void stripsAccentsAndLowercases() {
        assertEquals("acucar refinado", DescriptionNormalizer.normalize("AÇÚCAR REFINADO"));
    }

    @Test
    void collapsesWhitespace() {
        assertEquals("arroz tio joao 5kg", DescriptionNormalizer.normalize("  ARROZ   TIO\tJOAO  5KG  "));
    }

    @Test
    void stripsPunctuation() {
        assertEquals("leite integral 1l", DescriptionNormalizer.normalize("LEITE INTEGRAL, 1L."));
    }

    @Test
    void preservesAlphanumeric() {
        assertEquals("p1 7891234567890", DescriptionNormalizer.normalize("P1 7891234567890"));
    }

    @Test
    void emptyAndNullProduceEmptyString() {
        assertEquals("", DescriptionNormalizer.normalize(""));
        assertEquals("", DescriptionNormalizer.normalize(null));
    }

    @Test
    void expandsSingleTokenSefazAbbreviations() {
        assertEquals("manteiga elege 200g", DescriptionNormalizer.normalize("MANT ELEGE 200G"));
        assertEquals("mortadela seara kg", DescriptionNormalizer.normalize("MORTAD SEARA KG"));
        assertEquals("biscoito recheado", DescriptionNormalizer.normalize("BISC RECHEADO"));
    }

    @Test
    void expandsMultiWordAbbreviations() {
        assertEquals("leite em po ninho 380g", DescriptionNormalizer.normalize("LEITE PO NINHO 380G"));
    }

    @Test
    void abbreviatedAndSpelledOutConvergeToSameProduct() {
        assertEquals(
                DescriptionNormalizer.normalize("MANTEIGA ELEGE 200G"),
                DescriptionNormalizer.normalize("MANT ELEGE 200G"));
    }

    @Test
    void onlyExpandsWholeTokensNotSubstrings() {
        // "mantiqueira" merely starts with "mant" — must NOT become "manteigaiqueira"
        assertEquals("amendoim mantiqueira", DescriptionNormalizer.normalize("AMENDOIM MANTIQUEIRA"));
    }

    // ---------------------------------------------------------- normalizeOrNull

    @Test
    void normalizeOrNull_nullStaysNull() {
        assertNull(DescriptionNormalizer.normalizeOrNull(null));
    }

    @Test
    void normalizeOrNull_blankAndPunctuationOnlyBecomeNull() {
        // "no value" must stay "no value", not become "" — so dedup/norm columns don't
        // collide an accidental blank with a real empty string.
        assertNull(DescriptionNormalizer.normalizeOrNull(""));
        assertNull(DescriptionNormalizer.normalizeOrNull("   "));
        assertNull(DescriptionNormalizer.normalizeOrNull("--- , ."));
    }

    @Test
    void normalizeOrNull_stripsAccentsAndLowercases() {
        assertEquals("fermento biologico", DescriptionNormalizer.normalizeOrNull("Fermento Biológico"));
        assertEquals("nescafe", DescriptionNormalizer.normalizeOrNull("Nescafé"));
    }

    @Test
    void normalizeOrNull_isIdempotent() {
        var once = DescriptionNormalizer.normalizeOrNull("Café Pilão");
        assertEquals(once, DescriptionNormalizer.normalizeOrNull(once));
        assertEquals("cafe pilao", once);
    }
}
