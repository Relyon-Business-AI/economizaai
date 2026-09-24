package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Promotes CONFIRMED brand matches into curated registry aliases: a product whose
 * brand we already know (typically from its EAN) plus a receipt description that
 * writes that brand in an abbreviated form ("ferm bio d benta 10g" for "Dona
 * Benta") yields a deterministic alias {@code d benta → Dona Benta}.
 *
 * <p>This is the learning loop the strategy calls for, sourced from data we
 * already have instead of a (non-existent) user brand-correction flow. Turning
 * fuzzy hits into exact aliases makes future matches deterministic, auditable,
 * and eventually lets the fuzzy fallback be tightened or disabled.
 *
 * <p>Conflict safety: a candidate key that different products would map to
 * different brands is dropped — only unambiguous keys become aliases. New rows
 * are tagged {@code source=LEARNED_ALIAS} so they can be cleaned up separately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandAliasPromotionService {

    private static final String SOURCE = "LEARNED_ALIAS";

    private final ProductAliasRepository productAliasRepository;
    private final BrandRegistryEntryRepository brandRepository;
    private final BrandExtractor brandExtractor;

    /**
     * Backfill: scan every branded product's descriptions and promote each
     * unambiguous abbreviated brand form into a registry alias. Reloads the
     * in-memory brand snapshot when anything was added.
     */
    @Transactional
    public PromotionOutcome promoteFromKnownBrands() {
        var brandByKey = new HashMap<String, String>();
        var conflicting = new HashSet<String>();
        for (var row : productAliasRepository.findRawDescriptionsOfBrandedProducts()) {
            var rawDescription = (String) row[0];
            var brand = (String) row[1];
            for (var key : brandExtractor.abbreviationCandidates(rawDescription, brand)) {
                registerCandidate(brandByKey, conflicting, key, brand);
            }
        }
        conflicting.forEach(brandByKey::remove);
        var created = persistNewAliases(brandByKey);
        if (created > 0) brandExtractor.reload();
        var outcome = new PromotionOutcome(created, conflicting.size());
        log.info("brand.alias_promotion.backfill created={} conflicts={}", created, conflicting.size());
        return outcome;
    }

    /**
     * Learn aliases from a single product's descriptions — called right after an
     * admin sets/corrects a product's brand, so the correction propagates without
     * waiting for the next backfill. Safe to call with a blank brand (no-op).
     */
    @Transactional
    public int promoteForProduct(String brand, List<String> rawDescriptions) {
        if (brand == null || brand.isBlank() || rawDescriptions == null) return 0;
        var brandByKey = new HashMap<String, String>();
        var conflicting = new HashSet<String>();
        for (var rawDescription : rawDescriptions) {
            for (var key : brandExtractor.abbreviationCandidates(rawDescription, brand)) {
                registerCandidate(brandByKey, conflicting, key, brand);
            }
        }
        conflicting.forEach(brandByKey::remove);
        var created = persistNewAliases(brandByKey);
        if (created > 0) brandExtractor.reload();
        return created;
    }

    private void registerCandidate(Map<String, String> brandByKey, HashSet<String> conflicting,
                                   String key, String brand) {
        var existing = brandByKey.putIfAbsent(key, brand);
        if (existing != null && !existing.equalsIgnoreCase(brand)) conflicting.add(key);
    }

    private int persistNewAliases(Map<String, String> brandByKey) {
        var toSave = new ArrayList<BrandRegistryEntry>();
        for (var entry : brandByKey.entrySet()) {
            if (brandRepository.findByNormalizedKey(entry.getKey()).isPresent()) continue;
            toSave.add(BrandRegistryEntry.builder()
                    .normalizedKey(entry.getKey())
                    .displayName(entry.getValue().trim())
                    .source(SOURCE)
                    .build());
            log.info("brand.alias_promoted key='{}' brand='{}'", entry.getKey(), entry.getValue());
        }
        if (!toSave.isEmpty()) brandRepository.saveAll(toSave);
        return toSave.size();
    }

    public record PromotionOutcome(int created, int conflictsSkipped) {}
}
