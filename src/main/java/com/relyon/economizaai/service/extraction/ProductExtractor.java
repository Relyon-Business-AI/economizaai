package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier.Confidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the extraction cascade:
 *   1. PackSizeExtractor (regex)
 *   2. BrandExtractor (registry)
 *   3. DictionaryClassifier (curated + auto-promoted learned entries)
 *   4. ProductNameSubcategoryClassifier (keyword, HIGH-only) — fills the fine taxonomy
 *      node when the dictionary/catalog gave none, for free at ingestion.
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
    private final CategoryResolver categoryResolver;

    public ProductExtraction extract(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return ProductExtraction.EMPTY;
        }
        var packSize = PackSizeExtractor.extract(rawDescription);
        var dictHit = dictionaryClassifier.classify(rawDescription);
        var brand = dictHit.brand() != null ? dictHit.brand() : brandExtractor.find(rawDescription);

        var genericName = dictHit.genericName();
        var category = dictHit.category();
        var categoryId = dictHit.categoryId();
        // Fase 4: sem nó do dicionário/catálogo, tenta o classificador de keyword (grátis,
        // determinístico). Só confia em HIGH; não sobrescreve um nó já resolvido.
        if (categoryId == null) {
            // Normaliza igual ao recategorizador (que passa o nome normalizado) e ao
            // dicionário: as regras de keyword casam por fronteira de palavra em tokens
            // limpos, então a descrição crua (com pontuação) perderia matches.
            var leaf = ProductNameSubcategoryClassifier.classify(DescriptionNormalizer.normalize(rawDescription));
            if (leaf.confidence() == Confidence.HIGH) {
                categoryId = categoryResolver.idForSlug(leaf.slug());
            }
        }
        var source = (category != null || genericName != null)
                ? dictHit.source()
                : CategorizationSource.NONE;

        return new ProductExtraction(genericName, brand, packSize.size(), packSize.unit(),
                category, categoryId, source);
    }
}
