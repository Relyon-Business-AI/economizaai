package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the extraction cascade:
 *   1. PackSizeExtractor (regex)             — always
 *   2. BrandExtractor (registry)             — always
 *   3. DictionaryClassifier (curated CSV
 *      + auto-promoted learned entries)      — primary for genericName + category
 *   4. MlClassifierService (Naive Bayes)     — fallback ONLY for fields the
 *                                              dictionary missed, and only
 *                                              when confidence ≥ threshold
 *
 * Returns a {@link ProductExtraction} carrying the merged result and a
 * {@link CategorizationSource} that records which layer set the category.
 * The source is later used both to decide what to train on (we never
 * train on raw ML output) and to power audit/debug ("why is this OTHER?").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductExtractor {

    private final BrandExtractor brandExtractor;
    private final DictionaryClassifier dictionaryClassifier;
    private final MlClassifierService mlClassifier;

    public ProductExtraction extract(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return ProductExtraction.EMPTY;
        }
        var packSize = PackSizeExtractor.extract(rawDescription);
        var brand = brandExtractor.find(rawDescription);
        var dictHit = dictionaryClassifier.classify(rawDescription);

        var genericName = dictHit.genericName();
        var category = dictHit.category();
        var source = (category != null || genericName != null)
                ? dictHit.source()
                : CategorizationSource.NONE;

        var applyMl = mlClassifier.isReady() && mlClassifier.isCategoryApplyEnabled();
        if (category == null && applyMl) {
            var prediction = mlClassifier.predictCategory(rawDescription);
            if (prediction.isConfident(mlClassifier.getConfidenceThreshold())) {
                category = prediction.label();
                source = CategorizationSource.ML;
                log.info("extract.ml.category.hit confidence={} predicted={} description='{}'",
                        String.format("%.2f", prediction.confidence()), category, rawDescription);
            } else {
                log.debug("extract.ml.category.below_threshold confidence={} predicted={} description='{}'",
                        String.format("%.2f", prediction.confidence()),
                        prediction.label(), rawDescription);
            }
        }

        if (genericName == null && applyMl) {
            var prediction = mlClassifier.predictGenericName(rawDescription);
            if (prediction.isConfident(mlClassifier.getConfidenceThreshold())) {
                genericName = prediction.label();
                if (source == CategorizationSource.NONE) source = CategorizationSource.ML;
                log.info("extract.ml.genericName.hit confidence={} predicted='{}' description='{}'",
                        String.format("%.2f", prediction.confidence()), genericName, rawDescription);
            }
        }

        return new ProductExtraction(genericName, brand, packSize.size(), packSize.unit(), category, source);
    }
}
