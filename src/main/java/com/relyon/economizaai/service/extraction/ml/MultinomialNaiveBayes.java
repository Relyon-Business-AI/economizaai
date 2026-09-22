package com.relyon.economizaai.service.extraction.ml;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Multinomial Naive Bayes classifier with Laplace (add-one) smoothing.
 *
 * Trained in-memory on a list of (features, label) examples. After train(),
 * predict(features) returns the most likely label and a [0..1] confidence
 * derived from the relative log-probability gap between top and runner-up.
 *
 * Pure Java, no third-party dep — chosen over Smile/Tribuo to keep the JAR
 * small and the algorithm fully inspectable. NB is the right baseline for
 * short-text classification with few labels and limited training data
 * (which is exactly our case in the first months).
 *
 * Type parameter T is the label type (e.g. ProductCategory or String).
 */
public final class MultinomialNaiveBayes<T> {

    private final Map<T, Map<String, Long>> featureCountsByLabel = new HashMap<>();
    private final Map<T, Long> totalFeaturesByLabel = new HashMap<>();
    private final Map<T, Long> documentCountsByLabel = new HashMap<>();
    private final Set<String> vocabulary = new HashSet<>();
    private long totalDocuments = 0;

    public void train(List<Example<T>> examples,
                      Function<String, List<String>> featureExtractor) {
        Objects.requireNonNull(examples);
        Objects.requireNonNull(featureExtractor);
        clear();
        for (var example : examples) {
            var label = example.label();
            if (label == null) continue;
            var features = featureExtractor.apply(example.text());
            if (features.isEmpty()) continue;
            documentCountsByLabel.merge(label, 1L, Long::sum);
            totalDocuments++;
            var labelCounts = featureCountsByLabel.computeIfAbsent(label, k -> new HashMap<>());
            for (var feature : features) {
                labelCounts.merge(feature, 1L, Long::sum);
                totalFeaturesByLabel.merge(label, 1L, Long::sum);
                vocabulary.add(feature);
            }
        }
    }

    public MlPrediction<T> predict(List<String> features) {
        if (totalDocuments == 0 || features.isEmpty()) {
            return MlPrediction.empty();
        }
        var vocabSize = vocabulary.size();
        T bestLabel = null;
        double bestLogProb = Double.NEGATIVE_INFINITY;
        double secondBestLogProb = Double.NEGATIVE_INFINITY;
        var logProbs = new HashMap<T, Double>();

        for (var label : documentCountsByLabel.keySet()) {
            var priorLogProb = Math.log((double) documentCountsByLabel.get(label) / totalDocuments);
            var labelCounts = featureCountsByLabel.get(label);
            var totalForLabel = totalFeaturesByLabel.getOrDefault(label, 0L);
            var logProb = priorLogProb;
            for (var feature : features) {
                var count = labelCounts.getOrDefault(feature, 0L);
                // Laplace (add-one) smoothing
                logProb += Math.log((double) (count + 1) / (totalForLabel + vocabSize));
            }
            logProbs.put(label, logProb);
            if (logProb > bestLogProb) {
                secondBestLogProb = bestLogProb;
                bestLogProb = logProb;
                bestLabel = label;
            } else if (logProb > secondBestLogProb) {
                secondBestLogProb = logProb;
            }
        }

        var confidence = computeConfidence(bestLogProb, logProbs);
        return new MlPrediction<>(bestLabel, confidence);
    }

    /**
     * Confidence = exp(bestLogProb) / sum(exp(logProb)) — softmax normalisation.
     * Subtracts the max log-prob first to avoid underflow.
     */
    private double computeConfidence(double bestLogProb, Map<T, Double> logProbs) {
        var sumExp = 0.0;
        for (var lp : logProbs.values()) {
            sumExp += Math.exp(lp - bestLogProb);
        }
        return 1.0 / sumExp;
    }

    public int trainingSize() {
        return (int) totalDocuments;
    }

    public int labelCount() {
        return documentCountsByLabel.size();
    }

    public int vocabularySize() {
        return vocabulary.size();
    }

    private void clear() {
        featureCountsByLabel.clear();
        totalFeaturesByLabel.clear();
        documentCountsByLabel.clear();
        vocabulary.clear();
        totalDocuments = 0;
    }

    public record Example<T>(String text, T label) {}
}
