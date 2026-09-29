package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.service.admin.AdminProductService;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the catalog in sync with dictionary changes WITHOUT a manual run.
 * Once a night it:
 * <ol>
 *   <li>auto-promotes stable classifications into the learned dictionary (self-healing
 *       loop) so recurring patterns become free/instant rules without human approval;</li>
 *   <li>applies TRUSTED (dictionary/learned) category corrections to existing products;</li>
 *   <li>records a quality snapshot so any drift/regression surfaces in the trend;</li>
 *   <li>derives new Brazilian brands from the EAN catalog and promotes brand aliases;</li>
 *   <li>retries the UNMATCHED backlog against the current rules.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategorizerMaintenanceJob {

    private final AdminProductService adminProductService;
    private final CategorizationQualityService categorizationQualityService;
    private final CategorizerAdminService categorizerAdminService;
    private final BrandAliasPromotionService brandAliasPromotionService;
    private final CanonicalizationService canonicalizationService;
    private final AutoPromotionService autoPromotionService;

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
        autoPromote();
        try {
            var recategorized = adminProductService.recategorizeApply();
            categorizationQualityService.measureAndRecord(CategorizationQualityTrigger.BACKFILL);
            log.info("categorizer.maintenance.done recategorized={} skippedUser={} unchanged={} (quality snapshot recorded)",
                    recategorized.updated(), recategorized.skippedUserOverrides(), recategorized.unchanged());
        } catch (RuntimeException ex) {
            log.error("categorizer.maintenance.failed", ex);
        }
        maintainBrands();
    }

    /** Self-healing loop: consolidate stable classifications into learned rules. Isolated
     *  in its own try/catch so a failure here never blocks recategorize/brands/retry. */
    private void autoPromote() {
        try {
            var promotion = autoPromotionService.promote();
            log.info("categorizer.maintenance.auto_promote promoted={} skippedHuman={} skippedAgreement={} skippedSamples={} learnedTotal={}",
                    promotion.promoted(), promotion.skippedDueToHuman(), promotion.skippedDueToAgreement(),
                    promotion.skippedDueToSamples(), promotion.learnedTotal());
        } catch (RuntimeException ex) {
            log.error("categorizer.maintenance.auto_promote_failed", ex);
        }
    }

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
