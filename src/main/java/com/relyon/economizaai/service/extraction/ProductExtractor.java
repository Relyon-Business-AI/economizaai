package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the extraction cascade:
 *   1. PackSizeExtractor (regex)
 *   2. BrandExtractor (registry)
 *   3. DictionaryClassifier (curated + auto-promoted learned entries)
 *
 * Returns a {@link ProductExtraction} carrying the merged result and a
 * {@link CategorizationSource} that records which layer set the category.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductExtractor {

    private final BrandExtractor brandExtractor;
    private final DictionaryClassifier dictionaryClassifier;

    public ProductExtraction extract(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return ProductExtraction.EMPTY;
        }
        var packSize = PackSizeExtractor.extract(rawDescription);
        var dictHit = dictionaryClassifier.classify(rawDescription);
        var brand = dictHit.brand() != null ? dictHit.brand() : brandExtractor.find(rawDescription);

        var genericName = dictHit.genericName();
        var category = dictHit.category();
        var source = (category != null || genericName != null)
                ? dictHit.source()
                : CategorizationSource.NONE;

        return new ProductExtraction(genericName, brand, packSize.size(), packSize.unit(), category, source);
    }
}
