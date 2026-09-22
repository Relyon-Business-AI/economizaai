package com.relyon.economizaai.service.extraction.ml;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultinomialNaiveBayesTest {

    @Test
    void predictsCorrectClassAfterTraining() {
        var nb = new MultinomialNaiveBayes<String>();
        nb.train(List.of(
                new MultinomialNaiveBayes.Example<>("arroz tio joao 5kg", "GROCERIES"),
                new MultinomialNaiveBayes.Example<>("arroz integral 1kg", "GROCERIES"),
                new MultinomialNaiveBayes.Example<>("arroz parboilizado", "GROCERIES"),
                new MultinomialNaiveBayes.Example<>("limpador veja 500ml", "CLEANING"),
                new MultinomialNaiveBayes.Example<>("desinfetante pinho 1l", "CLEANING"),
                new MultinomialNaiveBayes.Example<>("sabao em po omo 2kg", "CLEANING")
        ), CharNGramFeatureExtractor::extract);

        var arroz = nb.predict(CharNGramFeatureExtractor.extract("arroz jasmim 5kg"));
        assertEquals("GROCERIES", arroz.label());
        assertTrue(arroz.confidence() > 0.8, "expected high confidence for clear groceries example");

        var cleaning = nb.predict(CharNGramFeatureExtractor.extract("limpador multiuso 1l"));
        assertEquals("CLEANING", cleaning.label());
    }

    @Test
    void emptyTrainingProducesEmptyPrediction() {
        var nb = new MultinomialNaiveBayes<String>();
        var prediction = nb.predict(List.of("anything"));
        assertNull(prediction.label());
        assertEquals(0.0, prediction.confidence());
    }

    @Test
    void confidenceIsHigherWhenSignalIsStronger() {
        var nb = new MultinomialNaiveBayes<String>();
        nb.train(List.of(
                new MultinomialNaiveBayes.Example<>("xxxxxxxx", "A"),
                new MultinomialNaiveBayes.Example<>("xxxxxxxy", "A"),
                new MultinomialNaiveBayes.Example<>("xxxxxxx1", "A"),
                new MultinomialNaiveBayes.Example<>("yyyyyyy1", "B")
        ), CharNGramFeatureExtractor::extract);

        var strongA = nb.predict(CharNGramFeatureExtractor.extract("xxxxxxxx"));
        var ambiguous = nb.predict(CharNGramFeatureExtractor.extract("xy12345"));
        assertEquals("A", strongA.label());
        assertTrue(strongA.confidence() > ambiguous.confidence());
    }

    @Test
    void recordsTrainingMetadata() {
        var nb = new MultinomialNaiveBayes<String>();
        nb.train(List.of(
                new MultinomialNaiveBayes.Example<>("hello world", "X"),
                new MultinomialNaiveBayes.Example<>("foo bar", "Y")
        ), CharNGramFeatureExtractor::extract);
        assertEquals(2, nb.trainingSize());
        assertEquals(2, nb.labelCount());
        assertTrue(nb.vocabularySize() > 0);
    }
}
