package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationExplanation;
import com.relyon.economizaai.dto.response.CategorizationExplanation.DictionaryHit;
import com.relyon.economizaai.dto.response.CategorizationExplanation.MlGuess;
import com.relyon.economizaai.dto.response.MlClassificationResponse;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import com.relyon.economizaai.service.extraction.ml.MlPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Dry-run categorization for debugging — runs the same cascade as ingestion
 * ({@link ProductExtractor}) over an arbitrary description and exposes both the
 * final decision and each layer's contribution, without persisting anything.
 *
 * <p>Powers {@code GET /categorizer/classify}. Use it to answer "why did
 * 'Milho' come out as PERSONAL_CARE?" — the {@code source} + dictionary/ML
 * breakdown say whether it was a bad dictionary entry or an over-confident ML guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationDebugService {

    private final ProductExtractor productExtractor;
    private final DictionaryClassifier dictionaryClassifier;
    private final MlClassifierService mlClassifier;

    public CategorizationExplanation explain(String description) {
        var extraction = productExtractor.extract(description);
        var dict = dictionaryClassifier.classify(description);
        var threshold = mlClassifier.getConfidenceThreshold();
        var mlCat = mlClassifier.predictCategory(description);
        var mlGen = mlClassifier.predictGenericName(description);

        log.info("categorizer.explain input='{}' category={} source={}",
                description, extraction.category(), extraction.categorizationSource());

        return new CategorizationExplanation(
                description,
                extraction.category(),
                extraction.genericName(),
                extraction.brand(),
                extraction.packSize(),
                extraction.packUnit(),
                extraction.categorizationSource(),
                new DictionaryHit(dict.genericName(), dict.category(), dict.source()),
                categoryGuess(mlCat, threshold),
                genericNameGuess(mlGen, threshold),
                mlClassifier.isReady(),
                mlClassifier.isReady() && mlClassifier.isCategoryApplyEnabled(),
                threshold);
    }

    public List<CategorizationExplanation> explainAll(List<String> descriptions) {
        if (descriptions == null) return List.of();
        return descriptions.stream().filter(Objects::nonNull).map(this::explain).toList();
    }

    /** ML-only view: the model's raw prediction, ignoring dictionary + the apply gate. */
    public MlClassificationResponse mlPredict(String description) {
        var threshold = mlClassifier.getConfidenceThreshold();
        var category = mlClassifier.predictCategory(description);
        var genericName = mlClassifier.predictGenericName(description);
        var categoryLabel = category.label() == null ? null : category.label().name();
        return new MlClassificationResponse(
                description,
                new MlClassificationResponse.Guess(categoryLabel,
                        categoryLabel == null ? null : category.confidence(), category.isConfident(threshold)),
                new MlClassificationResponse.Guess(genericName.label(),
                        genericName.label() == null ? null : genericName.confidence(), genericName.isConfident(threshold)),
                mlClassifier.isReady(),
                threshold);
    }

    public List<MlClassificationResponse> mlPredictAll(List<String> descriptions) {
        if (descriptions == null) return List.of();
        return descriptions.stream().filter(Objects::nonNull).map(this::mlPredict).toList();
    }

    private static MlGuess categoryGuess(MlPrediction<ProductCategory> prediction, double threshold) {
        var label = prediction.label() == null ? null : prediction.label().name();
        return new MlGuess(label, label == null ? null : prediction.confidence(), prediction.isConfident(threshold));
    }

    private static MlGuess genericNameGuess(MlPrediction<String> prediction, double threshold) {
        return new MlGuess(prediction.label(), prediction.label() == null ? null : prediction.confidence(),
                prediction.isConfident(threshold));
    }
}
