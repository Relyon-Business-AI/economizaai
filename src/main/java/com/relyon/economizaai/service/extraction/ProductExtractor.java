package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier.Confidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

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
        // Fase 4 + refino: o classificador de keyword (grátis, determinístico, HIGH-only)
        // preenche a folha fina quando o dicionário/catálogo não deu nó — OU refina um nó
        // de GRUPO para uma subcategoria, desde que dentro do mesmo grupo (nunca contradiz).
        if (categoryId == null || !categoryResolver.isLeafNode(categoryId)) {
            // Normaliza igual ao recategorizador (que passa o nome normalizado) e ao
            // dicionário: as regras de keyword casam por fronteira de palavra em tokens
            // limpos, então a descrição crua (com pontuação) perderia matches.
            var leaf = ProductNameSubcategoryClassifier.classify(DescriptionNormalizer.normalize(rawDescription));
            if (leaf.confidence() == Confidence.HIGH) {
                var leafId = categoryResolver.idForSlug(leaf.slug());
                if (categoryId == null || sameGroup(categoryId, leafId)) {
                    categoryId = leafId;
                }
            }
        }
        var source = (category != null || genericName != null)
                ? dictHit.source()
                : CategorizationSource.NONE;

        return new ProductExtraction(genericName, brand, packSize.size(), packSize.unit(),
                category, categoryId, source);
    }

    /** True when both nodes resolve to the same top-level group — so a keyword refine never crosses groups. */
    private boolean sameGroup(UUID currentNodeId, UUID leafId) {
        if (leafId == null) return false;
        var currentGroup = categoryResolver.groupIdOf(currentNodeId);
        return currentGroup != null && currentGroup.equals(categoryResolver.groupIdOf(leafId));
    }
}
