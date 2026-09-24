package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.service.admin.AdminProductService;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the catalog and the ML model in sync with dictionary changes WITHOUT a
 * manual run — the "recategorize is manual" gap. Once a night it:
 * <ol>
 *   <li>retrains the ML on the latest trusted data (DICTIONARY/USER/CONSENSUS);</li>
 *   <li>applies TRUSTED (dictionary/learned) category corrections to existing
 *       products — the exact same conservative pass as
 *       {@code POST /admin/products/recategorize} (skips USER/MERCHANT locks,
 *       never applies ML suggestions);</li>
 *   <li>records a quality snapshot so any drift/regression surfaces in the trend.</li>
 * </ol>
 * Conservative by design: it only propagates suggestions the dictionary tier
 * already produces, and never overrides a human correction. Fully config-gated
 * ({@code economizaai.categorizer.maintenance.*}) so it can be disabled or retimed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategorizerMaintenanceJob {

    private final MlClassifierService mlClassifierService;
    private final AdminProductService adminProductService;
    private final CategorizationQualityService categorizationQualityService;
    private final CategorizerAdminService categorizerAdminService;
    private final BrandAliasPromotionService brandAliasPromotionService;
    private final CanonicalizationService canonicalizationService;

    @Value("${economizaai.categorizer.maintenance.enabled:true}")
    private boolean enabled;

    @Value("${economizaai.categorizer.maintenance.brand-derivation-min-products:2}")
    private int brandDerivationMinProducts;

    @Value("${economizaai.categorizer.maintenance.unmatched-retry-cap:1000}")
    private int unmatchedRetryCap;

    @Scheduled(cron = "${economizaai.categorizer.maintenance.cron:0 40 4 * * *}",
            zone = "${economizaai.categorizer.maintenance.zone:America/Sao_Paulo}")
    public void maintain() {
        if (!enabled) {
            log.debug("categorizer.maintenance.skipped reason=disabled");
            return;
        }
        try {
            mlClassifierService.retrain();
            var recategorized = adminProductService.recategorizeApply(false);
            categorizationQualityService.measureAndRecord(CategorizationQualityTrigger.BACKFILL);
            log.info("categorizer.maintenance.done recategorized={} skippedUser={} unchanged={} (retrained + quality snapshot recorded)",
                    recategorized.updated(), recategorized.skippedUserOverrides(), recategorized.unchanged());
        } catch (RuntimeException ex) {
            log.error("categorizer.maintenance.failed", ex);
        }
        maintainBrands();
    }

    /**
     * Keeps the brand registry in sync with the growing EAN catalog and receipt
     * corpus — the "derivation ran once and went stale" gap (Dog Chow sat in the
     * catalog for months without ever becoming a recognizable brand):
     * <ol>
     *   <li>derive new BRAZILIAN brands from the EAN catalog (789/790 only);</li>
     *   <li>promote confirmed abbreviated brand forms into registry aliases.</li>
     * </ol>
     * Isolated from the main block so a brand failure never blocks retrain/quality.
     */
    private void maintainBrands() {
        try {
            var derivation = categorizerAdminService.deriveBrandsFromEanCatalog(brandDerivationMinProducts, true);
            var aliases = brandAliasPromotionService.promoteFromKnownBrands();
            log.info("categorizer.maintenance.brands derived={} aliasesPromoted={} conflictsSkipped={}",
                    derivation.created(), aliases.created(), aliases.conflictsSkipped());
        } catch (RuntimeException ex) {
            log.error("categorizer.maintenance.brands_failed", ex);
        }
        retryUnmatchedBacklog();
    }

    /**
     * Retries the confirmed UNMATCHED backlog against the current rules — before
     * this, an orphan only got retried when an admin happened to save a curated
     * rule whose keyword it contained. Non-forcing (an item matching nothing stays
     * unmatched), and USER corrections are untouched by construction: unmatched
     * items have no product, so there is nothing human-made to override.
     */
    private void retryUnmatchedBacklog() {
        try {
            var outcome = canonicalizationService.retryUnmatched(unmatchedRetryCap);
            log.info("categorizer.maintenance.unmatched_retry scanned={} linked={}",
                    outcome.scanned(), outcome.linked());
        } catch (RuntimeException ex) {
            log.error("categorizer.maintenance.unmatched_retry_failed", ex);
        }
    }
}
