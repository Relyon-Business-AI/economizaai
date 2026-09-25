package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationExplanation;
import com.relyon.economizaai.dto.response.CategorizationExplanation.DictionaryHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Dry-run categorization for debugging — runs the same cascade as ingestion
 * over an arbitrary description and exposes the final decision and each layer's
 * contribution, without persisting anything.
 *
 * <p>Powers {@code GET /categorizer/classify}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationDebugService {

    private final ProductExtractor productExtractor;
    private final DictionaryClassifier dictionaryClassifier;

    public CategorizationExplanation explain(String description) {
        var extraction = productExtractor.extract(description);
        var dict = dictionaryClassifier.classify(description);

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
                new DictionaryHit(dict.genericName(), dict.category(), dict.source()));
    }

    public List<CategorizationExplanation> explainAll(List<String> descriptions) {
        if (descriptions == null) return List.of();
        return descriptions.stream().filter(Objects::nonNull).map(this::explain).toList();
    }
}
