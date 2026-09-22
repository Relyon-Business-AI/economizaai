package com.relyon.economizaai.service.extraction.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharNGramFeatureExtractorTest {

    @Test
    void extractsTrigramsThroughFiveGrams() {
        var grams = CharNGramFeatureExtractor.extract("arroz");
        // length 5 → 3-grams (3) + 4-grams (2) + 5-grams (1) = 6 grams
        assertEquals(6, grams.size());
        assertTrue(grams.contains("arr"));
        assertTrue(grams.contains("rroz"));
        assertTrue(grams.contains("arroz"));
    }

    @Test
    void normalizesAccentsAndCase() {
        var grams = CharNGramFeatureExtractor.extract("AÇÚCAR");
        assertTrue(grams.contains("acu"));
        assertFalse(grams.toString().contains("Ç"));
    }

    @Test
    void emptyForBlank() {
        assertTrue(CharNGramFeatureExtractor.extract("").isEmpty());
        assertTrue(CharNGramFeatureExtractor.extract(null).isEmpty());
    }
}
