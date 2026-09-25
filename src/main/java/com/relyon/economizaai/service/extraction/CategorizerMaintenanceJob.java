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
            var recategorized = adminProductService.recategorizeApply();
            categorizationQualityService.measureAndRecord(CategorizationQualityTrigger.BACKFILL);
            log.info("categorizer.maintenance.done recategorized={} skippedUser={} unchanged={} (quality snapshot recorded)",
                    recategorized.updated(), recategorized.skippedUserOverrides(), recategorized.unchanged());
        } catch (RuntimeException ex) {
            log.error("categorizer.maintenance.failed", ex);
        }
        maintainBrands();
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
