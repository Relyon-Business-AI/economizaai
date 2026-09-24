package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationExplanation;
import com.relyon.economizaai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizaai.dto.response.CuratedEntryResponse;
import com.relyon.economizaai.dto.response.LearnedEntryResponse;
import com.relyon.economizaai.dto.response.MlClassificationResponse;
import com.relyon.economizaai.dto.response.PhraseTokenSimulationResponse;
import com.relyon.economizaai.model.ConsensusGraduationAudit;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.service.extraction.AutoPromotionService;
import com.relyon.economizaai.service.extraction.BrandAliasPromotionService;
import com.relyon.economizaai.service.extraction.CategorizationBenchmarkService;
import com.relyon.economizaai.service.extraction.CategorizerAdminService;
import com.relyon.economizaai.service.extraction.ConsensusPromotionService;
import com.relyon.economizaai.service.extraction.CategorizationDebugService;
import com.relyon.economizaai.service.extraction.CategorizationQualityService;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.PhraseTokenSimulationService;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operational endpoints for the extraction pipeline. Read/debug endpoints are
 * open to any authenticated user; the model-training and catalog/dictionary-
 * mutating endpoints (retrain, promote-consensus, all the *\/import ones,
 * derive-from-catalog) are ADMIN-gated in {@code SecurityConfig}. Bulk import
 * lists are capped ({@value #MAX_IMPORT_BATCH}) to bound heap on huge payloads.
 */
@RestController
@RequestMapping("/api/v1/categorizer")
@RequiredArgsConstructor
@Validated
@Tag(name = "Categorizer", description = "ML classifier status and pipeline operational endpoints")
public class CategorizerController {

    static final int MAX_IMPORT_BATCH = 5000;

    private final MlClassifierService mlClassifier;
    private final AutoPromotionService autoPromotionService;
    private final CategorizationDebugService categorizationDebugService;
    private final CategorizationBenchmarkService categorizationBenchmarkService;
    private final CategorizationQualityService categorizationQualityService;
    private final ConsensusPromotionService consensusPromotionService;
    private final CategorizerAdminService categorizerAdminService;
    private final EanCatalogService eanCatalogService;
    private final BrandAliasPromotionService brandAliasPromotionService;
    private final PhraseTokenSimulationService phraseTokenSimulationService;

    /**
     * Promote user-correction consensus into deterministic knowledge: products
     * corrected by enough households graduate to a global category (source USER),
     * and recurring agreed tokens enter the learned dictionary. Source #2 of the
     * cascade, fed by user feedback. Scheduled daily; this is the manual trigger.
     */
    @PostMapping("/promote-consensus")
    public ResponseEntity<ConsensusPromotionService.ConsensusOutcome> promoteConsensus() {
        return ResponseEntity.ok(consensusPromotionService.promote());
    }

    /**
     * Categorization quality over the golden set. Returns the detailed report
     * (accuracyPct + failing cases) AND records a snapshot so the trend is kept.
     */
    // POST (not GET): it runs a CPU-heavy golden-set pass AND records a quality snapshot,
    // so it mutates state — must not be a cacheable/prefetchable GET. ADMIN-only in SecurityConfig.
    @PostMapping("/benchmark")
    public ResponseEntity<CategorizationBenchmarkResponse> benchmark() {
        var report = categorizationBenchmarkService.run();
        categorizationQualityService.record(CategorizationQualityTrigger.BENCHMARK, report);
        return ResponseEntity.ok(report);
    }

    /** Quality trend — recent snapshots (newest first) from benchmark runs + backfills. */
    @GetMapping("/quality/history")
    public ResponseEntity<List<CategorizationQualitySnapshotResponse>> qualityHistory(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(categorizationQualityService.history(limit));
    }

    /**
     * Dry-run: see exactly how one or more raw descriptions would be categorized,
     * with a per-layer breakdown (dictionary vs ML). Nothing is persisted.
     * Repeat the param for several terms: {@code ?description=Milho&description=Lays}.
     */
    @GetMapping("/classify")
    public ResponseEntity<List<CategorizationExplanation>> classify(
            @RequestParam(required = false, defaultValue = "") List<String> description) {
        return ResponseEntity.ok(categorizationDebugService.explainAll(description));
    }

    /**
     * ML-ONLY view (dev): the model's raw prediction for each term, ignoring the
     * dictionary and the apply gate. Use to inspect/improve the model in isolation.
     * The full chain (dictionary + ML + final decision) is {@code /classify}.
     */
    @GetMapping("/ml/predict")
    public ResponseEntity<List<MlClassificationResponse>> mlPredict(
            @RequestParam(required = false, defaultValue = "") List<String> description) {
        return ResponseEntity.ok(categorizationDebugService.mlPredictAll(description));
    }

    /**
     * What-if: simulate the dictionary/brand phrase-window size over the real
     * unmatched backlog (coverage) and the golden set (accuracy), for each N in
     * [minTokens, maxTokens]. Read-only — to adopt a window, set the env var
     * ECONOMIZAAI_CATEGORIZATION_MAX_PHRASE_TOKENS. ADMIN-only in SecurityConfig.
     */
    @GetMapping("/simulate")
    public ResponseEntity<PhraseTokenSimulationResponse> simulatePhraseTokens(
            @RequestParam(defaultValue = "3") int minTokens,
            @RequestParam(defaultValue = "6") int maxTokens,
            @RequestParam(defaultValue = "2000") int sampleSize) {
        return ResponseEntity.ok(
                phraseTokenSimulationService.simulate(minTokens, maxTokens, sampleSize));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var body = new LinkedHashMap<String, Object>();
        body.put("ready", mlClassifier.isReady());
        body.put("lastTrainedAt", mlClassifier.getLastTrainedAt());
        body.put("confidenceThreshold", mlClassifier.getConfidenceThreshold());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/retrain")
    public ResponseEntity<MlClassifierService.RetrainOutcome> retrain() {
        return ResponseEntity.ok(mlClassifier.retrain());
    }

    @PostMapping("/auto-promote")
    public ResponseEntity<AutoPromotionService.PromotionOutcome> autoPromote() {
        return ResponseEntity.ok(autoPromotionService.promote());
    }

    /**
     * Bulk-seeds the EAN catalog (step A2 in the canonicalization cascade).
     * Each entry maps a GTIN/EAN to a category, generic name, and brand.
     * Use {@code source=OPEN_FOOD_FACTS} for OPF imports, {@code CURATED_IMPORT}
     * for manually verified data. ADMIN-only once RBAC lands.
     */
    @PostMapping("/ean-catalog/import")
    public ResponseEntity<EanCatalogService.BulkImportOutcome> importEanCatalog(
            @Size(max = MAX_IMPORT_BATCH) @RequestBody List<EanCatalogService.EanImportRequest> entries) {
        return ResponseEntity.ok(eanCatalogService.bulkImport(entries));
    }

    /**
     * ADMIN. Bulk-import raw Open Food Facts rows — category tags are mapped to
     * our enum server-side. Body: <pre>[{"code":"789...","productName":"...",
     * "brands":"...","categoryTags":"en:beverages,en:sodas"}, ...]</pre>
     */
    @PostMapping("/ean-catalog/import-off")
    public ResponseEntity<EanCatalogService.BulkImportOutcome> importOpenFoodFacts(
            @Size(max = MAX_IMPORT_BATCH) @RequestBody List<EanCatalogService.OpenFoodFactsRow> rows) {
        return ResponseEntity.ok(eanCatalogService.bulkImportOpenFoodFacts(rows));
    }

    /**
     * Lists all products whose category was set by consensus promotion (source = CONSENSUS).
     * Use to review what the consensus job graduated before deciding whether to keep or revert.
     * Safe read — nothing is mutated.
     */
    @GetMapping("/consensus")
    public ResponseEntity<List<CategorizerAdminService.ConsensusProductView>> listConsensus() {
        return ResponseEntity.ok(categorizerAdminService.listConsensus());
    }

    /**
     * ADMIN. Consensus graduation audit trail — who (which households) promoted
     * which product to which category, newest first. Optional productId filter.
     * Makes a bad/gamed consensus traceable and reversible.
     */
    @GetMapping("/consensus/audit")
    public ResponseEntity<List<ConsensusGraduationAudit>> consensusAudit(
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(categorizerAdminService.consensusAudit(productId, limit));
    }

    /**
     * Wipes every auto-promoted and consensus-learned token from the DB and
     * in-memory dictionary, restoring the pipeline to the curated CSV baseline.
     * Use when suspected poisoning of the learned layer. ADMIN-only once RBAC lands.
     */
    @DeleteMapping("/learned")
    public ResponseEntity<CategorizerAdminService.ResetLearnedOutcome> resetLearned() {
        return ResponseEntity.ok(categorizerAdminService.resetLearned());
    }

    /**
     * Reverts all consensus-graduated products (source = CONSENSUS) back to
     * NONE so they can be re-evaluated from scratch. Combine with reset-learned
     * for a full pipeline rollback. ADMIN-only once RBAC lands.
     */
    @DeleteMapping("/consensus")
    public ResponseEntity<CategorizerAdminService.ResetConsensusOutcome> resetConsensus() {
        return ResponseEntity.ok(categorizerAdminService.resetConsensus());
    }

    /**
     * Bulk-seeds the learned dictionary without a redeployment. Entries with a
     * high {@code sampleCount} behave like curated entries. Ideal for importing
     * from Open Food Facts, GS1 Brazil, or admin-reviewed spreadsheets.
     * ADMIN-only once RBAC lands.
     *
     * <pre>
     * POST /categorizer/dictionary/import
     * [{"token":"racao","genericName":"Ração","category":"OTHER","sampleCount":999}, ...]
     * </pre>
     */
    @PostMapping("/dictionary/import")
    public ResponseEntity<CategorizerAdminService.BulkImportOutcome> bulkImportDictionary(
            @Size(max = MAX_IMPORT_BATCH) @RequestBody List<CategorizerAdminService.DictionaryImportRequest> entries) {
        return ResponseEntity.ok(categorizerAdminService.bulkImport(entries));
    }

    /**
     * ADMIN. Upsert curated dictionary entries (highest-priority tier) —
     * hot-reloaded, no deploy. Body:
     * <pre>[{"keyword":"arroz","genericName":"Arroz","category":"GROCERIES"}, ...]</pre>
     */
    @PostMapping("/dictionary/curated/import")
    public ResponseEntity<CategorizerAdminService.CuratedImportOutcome> bulkImportCurated(
            @Size(max = MAX_IMPORT_BATCH) @RequestBody List<CategorizerAdminService.CuratedImportRequest> entries) {
        return ResponseEntity.ok(categorizerAdminService.importCuratedEntries(entries));
    }

    /** ADMIN. Paginated list of curated dictionary entries; optional {@code q} substring on the keyword. */
    @GetMapping("/dictionary/curated")
    public ResponseEntity<Page<CuratedEntryResponse>> listCurated(
            @RequestParam(required = false) String q, @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(categorizerAdminService.listCurated(q, pageable));
    }

    /** ADMIN. Delete a single curated entry (surgical, unlike the wipe-all reset) — hot-reloads. */
    @DeleteMapping("/dictionary/curated/{id}")
    public ResponseEntity<Void> deleteCurated(@PathVariable UUID id) {
        categorizerAdminService.deleteCurated(id);
        return ResponseEntity.noContent().build();
    }

    /** ADMIN. Paginated list of learned (auto-promoted) entries; optional {@code q} substring on the token. */
    @GetMapping("/dictionary/learned")
    public ResponseEntity<Page<LearnedEntryResponse>> listLearned(
            @RequestParam(required = false) String q, @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(categorizerAdminService.listLearned(q, pageable));
    }

    /** ADMIN. Delete a single learned entry (surgical) — hot-reloads. */
    @DeleteMapping("/dictionary/learned/{id}")
    public ResponseEntity<Void> deleteLearned(@PathVariable UUID id) {
        categorizerAdminService.deleteLearned(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * ADMIN. Upsert brand-registry entries — hot-reloaded, no deploy. Body:
     * <pre>[{"key":"tio joao","displayName":"Tio João"}, ...]</pre>
     */
    /**
     * ADMIN. Autocomplete for the rule editor's Marca field — distinct brand
     * display names from the registry matching {@code q} (normalized), capped.
     * Lets admins pick an existing brand instead of free-typing (no typos/dupes).
     */
    @GetMapping("/brands")
    public ResponseEntity<List<String>> searchBrands(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(categorizerAdminService.searchBrands(q, limit));
    }

    /**
     * ADMIN. Full brand-registry rows (id + key + display + source) so noisy
     * DERIVED entries can be reviewed and deleted from the ops center.
     */
    @GetMapping("/brands/entries")
    public ResponseEntity<List<CategorizerAdminService.BrandEntryView>> listBrandEntries(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(categorizerAdminService.listBrandEntries(q, limit));
    }

    /** ADMIN. Removes one brand-registry entry and hot-reloads the detector. */
    @DeleteMapping("/brands/{id}")
    public ResponseEntity<Void> deleteBrand(@PathVariable UUID id) {
        categorizerAdminService.deleteBrand(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/brands/import")
    public ResponseEntity<CategorizerAdminService.BulkImportOutcome> bulkImportBrands(
            @Size(max = MAX_IMPORT_BATCH) @RequestBody List<CategorizerAdminService.BrandImportRequest> entries) {
        return ResponseEntity.ok(categorizerAdminService.importBrands(entries));
    }

    /**
     * ADMIN. Grow the brand registry from brand strings already in the EAN
     * catalog (Open Food Facts) — zero external calls. Variants are grouped by
     * normalized key (most frequent wins as display name); keys with fewer than
     * {@code minProducts} catalog occurrences are dropped as crowd-sourced
     * noise. Fill-only: existing registry entries are never overwritten.
     * {@code onlyBrazil} (default true) restricts to 789/790 EANs — the catalog
     * is ~97% foreign and deriving from it floods the registry with US chains.
     */
    @PostMapping("/brands/derive-from-catalog")
    public ResponseEntity<CategorizerAdminService.BrandDerivationOutcome> deriveBrandsFromCatalog(
            @RequestParam(defaultValue = "2") int minProducts,
            @RequestParam(defaultValue = "true") boolean onlyBrazil) {
        return ResponseEntity.ok(categorizerAdminService.deriveBrandsFromEanCatalog(minProducts, onlyBrazil));
    }

    /**
     * ADMIN. Promote confirmed brand matches into registry aliases: scans branded
     * products' receipt descriptions and turns each unambiguous abbreviated brand
     * form ("d benta" → Dona Benta) into a deterministic alias. Grows the registry
     * from data we already have; conflicting keys are skipped.
     */
    @PostMapping("/brands/promote-aliases")
    public ResponseEntity<BrandAliasPromotionService.PromotionOutcome> promoteBrandAliases() {
        return ResponseEntity.ok(brandAliasPromotionService.promoteFromKnownBrands());
    }

    /**
     * ADMIN. Upsert golden-set rows for the benchmark. Body:
     * <pre>[{"description":"ARROZ TIO J 5KG","expectedCategory":"GROCERIES",
     *   "expectedBrand":"Tio João","expectedPackSize":5,"expectedPackUnit":"KG"}, ...]</pre>
     */
    @PostMapping("/benchmark/import")
    public ResponseEntity<CategorizerAdminService.BulkImportOutcome> bulkImportBenchmark(
            @Size(max = MAX_IMPORT_BATCH) @RequestBody List<CategorizerAdminService.BenchmarkImportRequest> entries) {
        return ResponseEntity.ok(categorizerAdminService.importBenchmarkEntries(entries));
    }
}
