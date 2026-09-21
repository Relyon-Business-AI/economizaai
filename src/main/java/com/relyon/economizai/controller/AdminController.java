package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.MergeProductRequest;
import com.relyon.economizai.dto.request.MerchantSupportOverrideRequest;
import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.dto.request.SetProductBrandRequest;
import com.relyon.economizai.dto.request.SetMetricsExclusionRequest;
import com.relyon.economizai.dto.request.SetProductCategoryRequest;
import com.relyon.economizai.dto.request.UpdateSubscriptionTierRequest;
import com.relyon.economizai.dto.response.AcquisitionReportResponse;
import com.relyon.economizai.dto.response.AdminUserDetailResponse;
import com.relyon.economizai.dto.response.BrandBackfillResponse;
import com.relyon.economizai.dto.response.RetentionCohortResponse;
import com.relyon.economizai.dto.response.SubscriptionReportResponse;
import com.relyon.economizai.dto.response.BrandCoverageReportResponse;
import com.relyon.economizai.dto.response.AdminOverviewResponse;
import com.relyon.economizai.dto.response.CostReportResponse;
import com.relyon.economizai.dto.response.IngestionHealthResponse;
import com.relyon.economizai.dto.response.MarketIntelResponse;
import com.relyon.economizai.dto.response.UnmatchedReportResponse;
import com.relyon.economizai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizai.dto.response.DuplicateProductGroupResponse;
import com.relyon.economizai.dto.response.GreyMerchantResponse;
import com.relyon.economizai.dto.response.LlmReportResponse;
import com.relyon.economizai.dto.response.MissingBrandProductResponse;
import com.relyon.economizai.dto.response.ProductDeletionResponse;
import com.relyon.economizai.dto.response.OrphanedObservationsResponse;
import com.relyon.economizai.dto.response.ProductMergeResultResponse;
import com.relyon.economizai.dto.response.PurgeObservationsResponse;
import com.relyon.economizai.dto.response.RecategorizeReportResponse;
import com.relyon.economizai.dto.response.RecategorizeResultResponse;
import com.relyon.economizai.dto.response.RelevanceReportResponse;
import com.relyon.economizai.dto.response.StateCoverageResponse;
import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.ReceiptService;
import com.relyon.economizai.service.admin.AdminLlmService;
import com.relyon.economizai.service.admin.AdminMerchantService;
import com.relyon.economizai.service.admin.AdminNotificationService;
import com.relyon.economizai.service.admin.AdminProductService;
import com.relyon.economizai.service.admin.AdminDevService;
import com.relyon.economizai.service.admin.AdminReceiptService;
import com.relyon.economizai.service.admin.AdminUserService;
import com.relyon.economizai.service.analytics.AdminAnalyticsService;
import com.relyon.economizai.service.analytics.RetentionCohortService;
import com.relyon.economizai.service.analytics.meta.MetaAdSpendSyncJob;
import com.relyon.economizai.service.extraction.CategorizationQualityService;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.notifications.RelevanceReportService;
import com.relyon.economizai.service.admin.AdminOverviewService;
import com.relyon.economizai.service.admin.IngestionHealthService;
import com.relyon.economizai.service.admin.MarketIntelService;
import com.relyon.economizai.service.paidapi.CostReportService;
import com.relyon.economizai.service.sefaz.SefazIngestionService;
import com.relyon.economizai.service.sefaz.StateCoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints reserved for ROLE_ADMIN. Path-gated via SecurityConfig
 * (/api/v1/admin/** → hasRole("ADMIN")), so no per-method guard needed.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Operations restricted to ROLE_ADMIN")
public class AdminController {

    private final ReceiptService receiptService;
    private final AdminUserService adminUserService;
    private final AdminReceiptService adminReceiptService;
    private final AdminNotificationService adminNotificationService;
    private final AdminMerchantService adminMerchantService;
    private final AdminLlmService adminLlmService;
    private final AdminProductService adminProductService;
    private final CategorizationQualityService categorizationQualityService;
    private final MarketLocationService marketLocationService;
    private final RelevanceReportService relevanceReportService;
    private final CostReportService costReportService;
    private final IngestionHealthService ingestionHealthService;
    private final AdminOverviewService adminOverviewService;
    private final MarketIntelService marketIntelService;
    private final AdminAnalyticsService adminAnalyticsService;
    private final RetentionCohortService retentionCohortService;
    private final MetaAdSpendSyncJob metaAdSpendSyncJob;
    private final StateCoverageService stateCoverageService;
    private final SefazIngestionService sefazIngestionService;
    private final AdminDevService adminDevService;

    // Dev-only guard for the orphaned-observation bulk delete (off on prod).
    @Value("${economizai.admin.dev-cleanup-enabled:false}")
    private boolean devCleanupEnabled;

    // Dev-only guard for QA/e2e test-data seeding (off on prod).
    @Value("${economizai.admin.dev-seed-enabled:false}")
    private boolean devSeedEnabled;

    @PostMapping("/receipts/{id}/reparse")
    public ResponseEntity<ReceiptResponse> reparseReceipt(@PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.reparse(id));
    }

    @GetMapping("/users")
    public ResponseEntity<Page<AdminUserSummaryResponse>> listUsers(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.list(q, pageable));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<AdminUserDetailResponse> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(adminUserService.get(id));
    }

    /** Delete a user account and its dependents (test/garbage cleanup). Refuses ADMIN accounts. */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        adminUserService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Set a user's subscription tier (testing / promos / ops). PRO activates, FREE cancels. */
    @PutMapping("/users/{id}/subscription-tier")
    public ResponseEntity<AdminUserDetailResponse> setSubscriptionTier(
            @PathVariable UUID id, @Valid @RequestBody UpdateSubscriptionTierRequest request) {
        return ResponseEntity.ok(adminUserService.setTier(id, request.tier()));
    }

    /** Exclude/re-include a user from ALL metrics (hide store-review / robo test accounts) without deleting it. */
    @PatchMapping("/users/{id}/metrics-exclusion")
    public ResponseEntity<AdminUserDetailResponse> setMetricsExclusion(
            @PathVariable UUID id, @Valid @RequestBody SetMetricsExclusionRequest request) {
        return ResponseEntity.ok(adminUserService.setMetricsExclusion(id, request.excluded()));
    }

    @GetMapping("/receipts")
    public ResponseEntity<Page<ReceiptSummaryResponse>> listReceipts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String marketCnpj,
            @RequestParam(required = false) List<ProductCategory> category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID householdId,
            @RequestParam(required = false) UnidadeFederativa uf,
            @RequestParam(required = false) ReceiptStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminReceiptService.list(from, to, marketCnpj, category, q, householdId, uf, status, pageable));
    }

    @GetMapping("/receipts/{id}")
    public ResponseEntity<ReceiptResponse> getReceipt(@PathVariable UUID id) {
        return ResponseEntity.ok(adminReceiptService.get(id));
    }

    /**
     * Purge the anonymized community price observations a receipt contributed. Deleting
     * a receipt/account keeps those observations by design (LGPD); this removes them for
     * a bad or test receipt. Returns how many were removed.
     */
    @DeleteMapping("/receipts/{id}/observations")
    public ResponseEntity<PurgeObservationsResponse> purgeReceiptObservations(@PathVariable UUID id) {
        return ResponseEntity.ok(new PurgeObservationsResponse(adminReceiptService.purgeObservationsForReceipt(id)));
    }

    /** Count community observations left orphaned by account deletions — a dev-hygiene gauge
     * that a weekly job watches to confirm the nightly E2E purge keeps test garbage near zero. */
    @GetMapping("/observations/orphaned-count")
    public ResponseEntity<OrphanedObservationsResponse> orphanedObservationCount() {
        return ResponseEntity.ok(new OrphanedObservationsResponse(adminReceiptService.countOrphanedObservations()));
    }

    /**
     * Dev-only cleanup: bulk-delete every orphaned observation (deleted-account leftovers).
     * Disabled unless {@code economizai.admin.dev-cleanup-enabled=true} — on prod these are
     * LGPD-preserved anonymized aggregates and must never be mass-deleted.
     */
    @DeleteMapping("/observations/orphaned")
    public ResponseEntity<PurgeObservationsResponse> purgeOrphanedObservations() {
        if (!devCleanupEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(new PurgeObservationsResponse(adminReceiptService.deleteOrphanedObservations()));
    }

    /**
     * Dev-only: plant a discounted PENDING receipt (three lines with a per-item
     * paid price) so QA / e2e flows have deterministic promotions to exercise the
     * discount UI. Seeds the calling admin's household, or {@code targetEmail}'s
     * when given. Disabled unless {@code economizai.admin.dev-seed-enabled=true}.
     */
    @PostMapping("/dev/seed-discounted-receipt")
    public ResponseEntity<ReceiptResponse> seedDiscountedReceipt(@AuthenticationPrincipal User user,
                                                                 @RequestParam(required = false) String targetEmail) {
        if (!devSeedEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(adminDevService.seedDiscountedReceipt(user, targetEmail));
    }

    @PostMapping("/notifications/test")
    public ResponseEntity<Void> sendTestNotification(@Valid @RequestBody SendTestNotificationRequest request) {
        adminNotificationService.sendTest(request);
        return ResponseEntity.accepted().build();
    }

    /**
     * Relevance-filter validation report (engagement rates + suppression regret)
     * — the evidence for the SHADOW → ON decision. See RelevanceReportResponse.
     */
    @GetMapping("/notifications/relevance-report")
    public ResponseEntity<RelevanceReportResponse> relevanceReport(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(relevanceReportService.report(Math.max(1, days)));
    }

    /**
     * Paid-API cost report — total spend + breakdown by service (captcha vs
     * Infosimples) and by state over the last {@code days}, plus today's spend
     * against the global daily budget. Reads the paid_api_call ledger.
     */
    @GetMapping("/costs")
    public ResponseEntity<CostReportResponse> costReport(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(costReportService.report(days));
    }

    @Operation(summary = "Admin home overview",
            description = "Cross-area KPIs in one call: users (total/today/week/PRO), receipts (total/today + "
                    + "30-day parse rate), 7-day active households, global confirmed spend, and price-index size.")
    @GetMapping("/overview")
    public ResponseEntity<AdminOverviewResponse> overview() {
        return ResponseEntity.ok(adminOverviewService.overview());
    }

    @Operation(summary = "Market intelligence",
            description = "Collaborative index size + most-scanned products/markets, spend by category and by UF, "
                    + "aggregated across all households (confirmed, non-excluded data).")
    @GetMapping("/market-intel")
    public ResponseEntity<MarketIntelResponse> marketIntel() {
        return ResponseEntity.ok(marketIntelService.report());
    }

    @Operation(summary = "Ingestion pipeline health",
            description = "Receipt outcomes over the window: status mix, parse success rate, sweeper-timed-out "
                    + "(stuck) counts, per-UF outcomes, and the top failure reasons — the ops view for what's breaking.")
    @GetMapping("/ingestion-health")
    public ResponseEntity<IngestionHealthResponse> ingestionHealth(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ingestionHealthService.report(days));
    }

    @Operation(summary = "Acquisition dashboard",
            description = "Signups over the window with the funnel (verified → activated → PRO), derived-channel "
                    + "and campaign breakdowns, and Meta ad-spend + cost-per-signup when the integration is connected.")
    @GetMapping("/analytics/acquisition")
    public ResponseEntity<AcquisitionReportResponse> acquisition(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean includeInternal) {
        return ResponseEntity.ok(adminAnalyticsService.acquisition(days, includeInternal));
    }

    @Operation(summary = "Subscription mix",
            description = "Tier distribution plus PRO split into genuinely paying vs promo/admin grants.")
    @GetMapping("/analytics/subscriptions")
    public ResponseEntity<SubscriptionReportResponse> subscriptionAnalytics(
            @RequestParam(defaultValue = "false") boolean includeInternal) {
        return ResponseEntity.ok(adminAnalyticsService.subscriptions(includeInternal));
    }

    @Operation(summary = "Weekly cohort retention",
            description = "The retention triangle: users grouped by signup week, then how many of that same "
                    + "group scanned a receipt 0/1/2… weeks later — plus a per-channel pooled curve to see which "
                    + "acquisition source brings users that stick. weeks = number of cohort weeks (4–16).")
    @GetMapping("/analytics/retention-cohorts")
    public ResponseEntity<RetentionCohortResponse> retentionCohorts(
            @RequestParam(defaultValue = "8") int weeks,
            @RequestParam(defaultValue = "false") boolean includeInternal) {
        return ResponseEntity.ok(retentionCohortService.cohorts(weeks, includeInternal));
    }

    @Operation(summary = "Sync Meta ad spend now",
            description = "Triggers the Meta ad-spend sync immediately (instead of waiting for the daily cron). "
                    + "No-op returning 0 rows when the Meta integration is not configured.")
    @PostMapping("/analytics/ad-spend/sync")
    public ResponseEntity<Map<String, Integer>> syncAdSpend() {
        return ResponseEntity.ok(Map.of("rowsSynced", metaAdSpendSyncJob.syncNow()));
    }

    /**
     * Multi-state rollout map: per UF, whether ingestion is VERIFIED (dedicated
     * adapter) or EXPERIMENTAL (QR-portal + Infosimples fallback chain), and the
     * per-layer success/failure telemetry from real users' scans — the data for
     * deciding which state adapter to build next.
     */
    @GetMapping("/state-coverage")
    public ResponseEntity<StateCoverageResponse> stateCoverage() {
        return ResponseEntity.ok(stateCoverageService.report(
                sefazIngestionService.getVerifiedStates(), sefazIngestionService.experimentalStates()));
    }

    /**
     * Product catalog (paged) for curation. Optional filters: {@code q} (name/EAN
     * substring), {@code category} (e.g. OTHER — the review queue), {@code source}.
     */
    @GetMapping("/products")
    public ResponseEntity<Page<ProductResponse>> listProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) CategorizationSource source,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(adminProductService.listAll(q, category, source, pageable));
    }

    /**
     * Classify (or backfill) every still-UNKNOWN market's business segment from
     * its CNPJ's CNAE. Normally runs on a schedule; this triggers it on demand.
     */
    @PostMapping("/markets/classify-segments")
    public ResponseEntity<MarketLocationService.SegmentClassificationSummary> classifyMarketSegments() {
        return ResponseEntity.ok(marketLocationService.classifyPendingSegments());
    }

    /**
     * Review queue: merchants scanned by real users but outside every supported
     * segment (grey zone), ranked by scan volume. Their receipts are ingested
     * for the user but held out of the collaborative index pending a verdict.
     */
    @GetMapping("/merchants/grey")
    public ResponseEntity<List<GreyMerchantResponse>> greyMerchants() {
        return ResponseEntity.ok(adminMerchantService.listGreyMerchants());
    }

    /**
     * Verdict on a grey merchant: SUPPORTED promotes it (and backfills the index
     * from its confirmed receipts), BLOCKED rejects future scans, null override
     * reverts to segment-driven gating.
     */
    @PutMapping("/merchants/{cnpj}/support")
    public ResponseEntity<AdminMerchantService.SupportOverrideResult> setMerchantSupport(
            @PathVariable String cnpj, @RequestBody MerchantSupportOverrideRequest request) {
        return ResponseEntity.ok(adminMerchantService.setSupportOverride(cnpj, request.override()));
    }

    /** LLM teacher-layer health: labels, cost, override-rate KPI, disagreement queue. */
    @GetMapping("/llm/report")
    public ResponseEntity<LlmReportResponse> llmReport(
            @RequestParam(defaultValue = "3") int enrichmentMaxAttempts) {
        return ResponseEntity.ok(adminLlmService.report(enrichmentMaxAttempts));
    }

    /** Human verdict on an LLM disagreement: accept applies it as source USER; reject closes it. */
    @PostMapping("/llm/disagreements/{id}/resolve")
    public ResponseEntity<Void> resolveLlmDisagreement(@PathVariable UUID id,
                                                       @RequestParam boolean accept) {
        adminLlmService.resolveDisagreement(id, accept);
        return ResponseEntity.noContent().build();
    }

    /** Re-run brand extraction to fill products missing a brand (after registry edits). */
    @PostMapping("/products/refresh-brands")
    public ResponseEntity<BrandBackfillResponse> refreshBrands() {
        return ResponseEntity.ok(adminProductService.backfillBrands());
    }

    /** Dry-run: measure how well the brand registry covers the product base (no writes). */
    @GetMapping("/products/brand-coverage")
    public ResponseEntity<BrandCoverageReportResponse> brandCoverage() {
        return ResponseEntity.ok(adminProductService.brandCoverageReport());
    }

    /** Matching KPI: UNMATCHED rate + the most frequent orphan descriptions. */
    @GetMapping("/products/unmatched-report")
    public ResponseEntity<UnmatchedReportResponse> unmatchedReport(
            @RequestParam(defaultValue = "30") int topN) {
        return ResponseEntity.ok(adminProductService.unmatchedReport(topN));
    }

    @GetMapping("/products/missing-brand")
    public ResponseEntity<Page<MissingBrandProductResponse>> listMissingBrand(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminProductService.listMissingBrand(pageable));
    }

    @PatchMapping("/products/{id}/brand")
    public ResponseEntity<ProductResponse> setProductBrand(
            @PathVariable UUID id, @Valid @RequestBody SetProductBrandRequest request) {
        return ResponseEntity.ok(adminProductService.setBrand(id, request));
    }

    /** Set the product's GLOBAL category and lock it as a manual (USER) decision. */
    @PatchMapping("/products/{id}/category")
    public ResponseEntity<ProductResponse> setProductCategory(
            @PathVariable UUID id, @Valid @RequestBody SetProductCategoryRequest request) {
        return ResponseEntity.ok(adminProductService.setCategory(id, request.category()));
    }

    /**
     * Delete a product and its dependents (catalog pruning of test/junk
     * rows). Refuses if the product still backs confirmed purchases unless
     * {@code force=true}; with force, those receipt items are detached.
     */
    @DeleteMapping("/products/{id}")
    public ResponseEntity<ProductDeletionResponse> deleteProduct(
            @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(adminProductService.delete(id, force));
    }

    @GetMapping("/products/duplicates")
    public ResponseEntity<List<DuplicateProductGroupResponse>> listDuplicates() {
        return ResponseEntity.ok(adminProductService.listDuplicateGroups());
    }

    @PostMapping("/products/{id}/merge")
    public ResponseEntity<ProductMergeResultResponse> mergeProduct(
            @PathVariable UUID id, @Valid @RequestBody MergeProductRequest request) {
        return ResponseEntity.ok(adminProductService.merge(id, request));
    }

    /** Dry-run: re-run the categorizer over the whole catalog and list mismatches (read-only). */
    @GetMapping("/products/recategorize")
    public ResponseEntity<RecategorizeReportResponse> recategorizeReport() {
        return ResponseEntity.ok(adminProductService.recategorizeReport());
    }

    /**
     * Apply re-categorization. Default applies only trusted (dictionary)
     * suggestions; pass {@code includeMl=true} to also apply ML suggestions.
     * Always skips USER-locked categories and null suggestions. Records a
     * quality snapshot afterwards so the backfill shows up in the trend.
     */
    @PostMapping("/products/recategorize")
    public ResponseEntity<RecategorizeResultResponse> recategorizeApply(
            @RequestParam(defaultValue = "false") boolean includeMl) {
        var result = adminProductService.recategorizeApply(includeMl);
        categorizationQualityService.measureAndRecord(CategorizationQualityTrigger.BACKFILL);
        return ResponseEntity.ok(result);
    }
}
