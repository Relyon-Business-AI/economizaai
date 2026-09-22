package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CuratedEntryResponse;
import com.relyon.economizaai.dto.response.LearnedEntryResponse;
import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.EanCatalogRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin-only operations for the categorization pipeline: restore to safe state
 * and bulk-seed the learned dictionary without a redeployment.
 *
 * <p><b>Reset-learned</b> wipes every auto-promoted/consensus-learned token
 * from the in-memory and DB layers, restoring the dictionary to the curated
 * baseline. Safe to run at any time — the curated table is the authoritative
 * "factory defaults" and is never touched by this operation.</p>
 *
 * <p><b>Reset-consensus</b> reverts all products that were globally graduated
 * by {@code ConsensusPromotionService} (source = CONSENSUS) back to NONE so
 * they can be re-evaluated by the current pipeline. Intended for use after
 * suspected data poisoning (bad actors gaming the consensus threshold).</p>
 *
 * <p><b>Bulk-import</b> inserts entries directly into the learned dictionary
 * table with a high {@code sampleCount}, making them behave like curated
 * entries without requiring a redeployment. Ideal for seeding the dictionary
 * from external data (Open Food Facts, GS1 Brazil, etc.).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizerAdminService {

    private static final EnumSet<CategorizationSource> CONSENSUS_SOURCES =
            EnumSet.of(CategorizationSource.CONSENSUS);

    private final LearnedDictionaryRepository learnedRepository;
    private final ProductRepository productRepository;
    private final DictionaryClassifier dictionaryClassifier;
    private final BrandExtractor brandExtractor;
    private final CuratedDictionaryEntryRepository curatedRepository;
    private final BrandRegistryEntryRepository brandRepository;
    private final CategorizationBenchmarkEntryRepository benchmarkRepository;
    private final EanCatalogRepository eanCatalogRepository;

    @Transactional
    public ResetLearnedOutcome resetLearned() {
        var count = (int) learnedRepository.count();
        learnedRepository.deleteAllInBatch();
        dictionaryClassifier.replaceLearnedEntries(Map.of());
        log.info("categorizer.reset_learned removed={}", count);
        return new ResetLearnedOutcome(count);
    }

    // ── Dictionary management (list + single delete) for the admin ops center ──

    @Transactional(readOnly = true)
    public Page<CuratedEntryResponse> listCurated(String query, Pageable pageable) {
        var page = (query == null || query.isBlank())
                ? curatedRepository.findAll(pageable)
                : curatedRepository.findByKeywordContainingIgnoreCase(query.trim().toLowerCase(), pageable);
        return page.map(CuratedEntryResponse::from);
    }

    @Transactional
    public void deleteCurated(UUID id) {
        curatedRepository.deleteById(id);
        dictionaryClassifier.reloadCuratedEntries();
        log.info("categorizer.curated_deleted id={}", id);
    }

    @Transactional(readOnly = true)
    public Page<LearnedEntryResponse> listLearned(String query, Pageable pageable) {
        var page = (query == null || query.isBlank())
                ? learnedRepository.findAll(pageable)
                : learnedRepository.findByNormalizedTokenContainingIgnoreCase(query.trim().toLowerCase(), pageable);
        return page.map(LearnedEntryResponse::from);
    }

    @Transactional
    public void deleteLearned(UUID id) {
        learnedRepository.deleteById(id);
        reloadLearnedSnapshot();
        log.info("categorizer.learned_deleted id={}", id);
    }

    @Transactional
    public ResetConsensusOutcome resetConsensus() {
        var consensusProducts = productRepository.findByCategorizationSourceIn(CONSENSUS_SOURCES);
        if (consensusProducts.isEmpty()) {
            log.info("categorizer.reset_consensus nothing_to_revert");
            return new ResetConsensusOutcome(0);
        }
        for (var product : consensusProducts) {
            product.setCategorizationSource(CategorizationSource.NONE);
            product.setCategory(null);
        }
        productRepository.saveAll(consensusProducts);
        log.info("categorizer.reset_consensus reverted={}", consensusProducts.size());
        return new ResetConsensusOutcome(consensusProducts.size());
    }

    @Transactional
    public BulkImportOutcome bulkImport(List<DictionaryImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);
        var tokens = entries.stream().map(DictionaryImportRequest::token).toList();
        var existingByToken = learnedRepository.findByNormalizedTokenIn(tokens).stream()
                .collect(Collectors.toMap(
                        LearnedDictionaryEntry::getNormalizedToken,
                        Function.identity()));
        var now = LocalDateTime.now();
        var toSave = new ArrayList<LearnedDictionaryEntry>();
        var skipped = 0;
        for (var req : entries) {
            if (req.token() == null || req.token().isBlank() || req.category() == null) {
                skipped++;
                continue;
            }
            var entry = existingByToken.getOrDefault(req.token(),
                    LearnedDictionaryEntry.builder()
                            .normalizedToken(req.token().trim().toLowerCase())
                            .sampleCount(0)
                            .promotedAt(now)
                            .build());
            entry.setCategory(req.category());
            entry.setGenericName(req.genericName());
            // High sample count signals high confidence — this entry won't be overridden by low-count promotions
            entry.setSampleCount(req.sampleCount() > 0 ? req.sampleCount() : 999);
            entry.setPromotedAt(now);
            toSave.add(entry);
        }
        learnedRepository.saveAll(toSave);

        var learnedTotal = reloadLearnedSnapshot();
        log.info("categorizer.bulk_import imported={} skipped={} learnedTotal={}", toSave.size(), skipped, learnedTotal);
        return new BulkImportOutcome(toSave.size(), skipped);
    }

    /** Rebuilds the in-memory classifier snapshot from the DB immediately; returns the learned-entry total. */
    private int reloadLearnedSnapshot() {
        var allEntries = learnedRepository.findAll();
        var snapshot = new LinkedHashMap<String, DictionaryClassifier.DictEntry>();
        for (var entry : allEntries) {
            snapshot.put(entry.getNormalizedToken(), new DictionaryClassifier.DictEntry(
                    entry.getGenericName(), entry.getCategory(), CategorizationSource.LEARNED_DICTIONARY));
        }
        dictionaryClassifier.replaceLearnedEntries(snapshot);
        return allEntries.size();
    }

    public List<ConsensusProductView> listConsensus() {
        return productRepository.findByCategorizationSourceIn(CONSENSUS_SOURCES).stream()
                .map(product -> new ConsensusProductView(
                        product.getId(), product.getEan(), product.getNormalizedName(),
                        product.getGenericName(), product.getBrand(), product.getCategory()))
                .toList();
    }

    /**
     * Upserts curated dictionary entries (the highest-priority tier) and
     * hot-reloads the in-memory snapshot — no deploy needed to grow it.
     */
    @Transactional
    public BulkImportOutcome importCuratedEntries(List<CuratedImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);
        var imported = 0;
        var skipped = 0;
        for (var request : entries) {
            if (request.keyword() == null || request.keyword().isBlank() || request.category() == null) {
                skipped++;
                continue;
            }
            var keyword = request.keyword().trim().toLowerCase();
            var entry = curatedRepository.findByKeyword(keyword)
                    .orElseGet(() -> CuratedDictionaryEntry.builder().keyword(keyword).build());
            entry.setGenericName(request.genericName());
            entry.setCategory(request.category());
            curatedRepository.save(entry);
            imported++;
        }
        dictionaryClassifier.reloadCuratedEntries();
        log.info("categorizer.curated_import imported={} skipped={}", imported, skipped);
        return new BulkImportOutcome(imported, skipped);
    }

    /** Upserts brand-registry entries and hot-reloads the in-memory snapshot. */
    @Transactional
    public BulkImportOutcome importBrands(List<BrandImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);
        var imported = 0;
        var skipped = 0;
        for (var request : entries) {
            if (request.key() == null || request.key().isBlank()
                    || request.displayName() == null || request.displayName().isBlank()) {
                skipped++;
                continue;
            }
            var normalizedKey = request.key().trim().toLowerCase();
            var entry = brandRepository.findByNormalizedKey(normalizedKey)
                    .orElseGet(() -> BrandRegistryEntry.builder().normalizedKey(normalizedKey).build());
            entry.setDisplayName(request.displayName().trim());
            brandRepository.save(entry);
            imported++;
        }
        brandExtractor.reload();
        log.info("categorizer.brand_import imported={} skipped={}", imported, skipped);
        return new BulkImportOutcome(imported, skipped);
    }

    // Short/common tokens that appear inside descriptions and would false-match if
    // treated as brand keys (Open Food Facts brand data is noisy — "BOLACHA DE
    // MANTEIGA" must NOT resolve to brand "De"). Also enforce a 4-char minimum.
    private static final int MIN_BRAND_KEY_LENGTH = 4;
    private static final Set<String> BRAND_KEY_STOPWORDS = Set.of(
            "de", "da", "do", "com", "sem", "para", "nat", "int", "und", "kit",
            "barra", "linha", "tipo", "casa", "boa", "vita", "life", "premium",
            "select", "gold", "extra", "class", "top", "mais", "novo", "nova");

    /**
     * Grows the brand registry from the brand strings already sitting in the
     * EAN catalog (Open Food Facts imports) — zero external calls. Crowd-sourced
     * data is messy, so: variants are grouped by normalized key (most frequent
     * wins as display name), keys shorter than {@value #MIN_BRAND_KEY_LENGTH}
     * chars / numeric / stopwords are dropped, and keys below {@code minProducts}
     * catalog occurrences are dropped as noise. Fill-only (curation wins); rows
     * are tagged {@code source=DERIVED} so they can be cleaned up separately.
     */
    @Transactional
    public BrandDerivationOutcome deriveBrandsFromEanCatalog(int minProducts) {
        var threshold = Math.max(1, minProducts);
        // Words the curated dictionary already knows are generic PRODUCT terms
        // (arroz, tomate, leite…). A brand key equal to one of these is noise —
        // "tomate" is never a brand — so skip it. Uses our own truth instead of
        // an ever-growing stopword list.
        // Normalize with the SAME normalizer the brand keys use (accent-stripping),
        // else an accented product word ("açúcar") won't match its stripped brand key.
        var productWords = curatedRepository.findAll().stream()
                .map(entry -> DescriptionNormalizer.normalize(entry.getKeyword()))
                .collect(java.util.stream.Collectors.toSet());
        var variantsByKey = new LinkedHashMap<String, List<EanCatalogRepository.BrandOccurrence>>();
        for (var occurrence : eanCatalogRepository.countByBrand()) {
            var key = DescriptionNormalizer.normalize(occurrence.getBrand());
            if (key.length() < MIN_BRAND_KEY_LENGTH || key.chars().allMatch(Character::isDigit)
                    || BRAND_KEY_STOPWORDS.contains(key) || productWords.contains(key)) {
                continue;
            }
            variantsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(occurrence);
        }
        var created = 0;
        var skippedExisting = 0;
        var belowThreshold = 0;
        for (var entry : variantsByKey.entrySet()) {
            var totalOccurrences = entry.getValue().stream()
                    .mapToLong(EanCatalogRepository.BrandOccurrence::getOccurrences).sum();
            if (totalOccurrences < threshold) {
                belowThreshold++;
                continue;
            }
            if (brandRepository.findByNormalizedKey(entry.getKey()).isPresent()) {
                skippedExisting++;
                continue;
            }
            var displayName = entry.getValue().stream()
                    .max(Comparator.comparingLong(EanCatalogRepository.BrandOccurrence::getOccurrences))
                    .map(EanCatalogRepository.BrandOccurrence::getBrand)
                    .orElseThrow();
            brandRepository.save(BrandRegistryEntry.builder()
                    .normalizedKey(entry.getKey())
                    .displayName(displayName.trim())
                    .source("DERIVED")
                    .build());
            created++;
        }
        if (created > 0) brandExtractor.reload();
        log.info("categorizer.brand_derivation created={} skippedExisting={} belowThreshold={} minProducts={}",
                created, skippedExisting, belowThreshold, threshold);
        return new BrandDerivationOutcome(created, skippedExisting, belowThreshold);
    }

    /** Upserts golden-set rows so the benchmark can grow without a deploy. */
    @Transactional
    public BulkImportOutcome importBenchmarkEntries(List<BenchmarkImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);
        var imported = 0;
        var skipped = 0;
        for (var request : entries) {
            if (request.description() == null || request.description().isBlank()
                    || request.expectedCategory() == null) {
                skipped++;
                continue;
            }
            var description = request.description().trim();
            var entry = benchmarkRepository.findByDescription(description)
                    .orElseGet(() -> CategorizationBenchmarkEntry.builder().description(description).build());
            entry.setExpectedCategory(request.expectedCategory());
            entry.setExpectedBrand(request.expectedBrand());
            entry.setExpectedPackSize(request.expectedPackSize());
            entry.setExpectedPackUnit(request.expectedPackUnit());
            benchmarkRepository.save(entry);
            imported++;
        }
        log.info("categorizer.benchmark_import imported={} skipped={}", imported, skipped);
        return new BulkImportOutcome(imported, skipped);
    }

    public record DictionaryImportRequest(String token, String genericName, ProductCategory category, int sampleCount) {}

    public record CuratedImportRequest(String keyword, String genericName, ProductCategory category) {}

    public record BrandImportRequest(String key, String displayName) {}

    public record BrandDerivationOutcome(int created, int skippedExisting, int belowThreshold) {}

    public record BenchmarkImportRequest(String description, ProductCategory expectedCategory,
                                         String expectedBrand, BigDecimal expectedPackSize,
                                         String expectedPackUnit) {}

    public record ResetLearnedOutcome(int removedEntries) {}

    public record ResetConsensusOutcome(int revertedProducts) {}

    public record BulkImportOutcome(int imported, int skipped) {}

    public record ConsensusProductView(
            UUID id, String ean, String normalizedName,
            String genericName, String brand, ProductCategory category) {}
}
