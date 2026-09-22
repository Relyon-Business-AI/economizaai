package com.relyon.economizaai.service.canonicalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaroWinklerSimilarityTest {

    @Test
    void identicalStringsScoreOne() {
        assertEquals(1.0, JaroWinklerSimilarity.score("arroz tio joao 5kg", "arroz tio joao 5kg"));
    }

    @Test
    void closeAbbreviationsScoreAboveThreshold() {
        var score = JaroWinklerSimilarity.score("arroz tio j 5kg", "arroz tio joao 5kg");
        assertTrue(score >= 0.85, "expected >= 0.85, was " + score);
    }

    @Test
    void unrelatedDescriptionsScoreLow() {
        var score = JaroWinklerSimilarity.score("arroz tio joao 5kg", "feijao preto kg");
        assertTrue(score < 0.7, "expected < 0.7, was " + score);
    }

    @Test
    void emptyOrNullScoresZero() {
        assertEquals(0.0, JaroWinklerSimilarity.score(null, "x"));
        assertEquals(0.0, JaroWinklerSimilarity.score("x", null));
        assertEquals(0.0, JaroWinklerSimilarity.score("", "x"));
    }

    @Test
    void prefixMatchBoostsScore() {
        var withPrefix = JaroWinklerSimilarity.score("leite integral 1l", "leite integral 1lt");
        var withoutPrefix = JaroWinklerSimilarity.score("leite integral 1l", "1l leite integral");
        assertTrue(withPrefix > withoutPrefix);
    }
}
