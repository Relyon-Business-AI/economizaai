package com.relyon.economizaai.service.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrandExtractorTest {

    private final BrandExtractor extractor = SeedFixtures.loadedBrandExtractor();

    @Test
    void findsTwoWordBrandFirst() {
        assertEquals("Tio João", extractor.find("ARROZ TIO JOAO TP1 5KG"));
    }

    @Test
    void findsAbbreviatedBrand() {
        assertEquals("Tio João", extractor.find("ARROZ TIO J TP1 5KG"));
    }

    @Test
    void findsSingleWordBrand() {
        assertEquals("Veja", extractor.find("LIMP COZ VEJA LIMAO SQ500ML"));
    }

    @Test
    void returnsNullForUnknownBrand() {
        assertNull(extractor.find("PRODUTO QUALQUER MARCA INEXISTENTE"));
    }

    @Test
    void normalizesAccents() {
        assertEquals("Itambé", extractor.find("LEITE INTEGRAL ITAMBE 1L"));
    }

    @Test
    void handlesBlankInput() {
        assertNull(extractor.find(""));
        assertNull(extractor.find(null));
    }
}
