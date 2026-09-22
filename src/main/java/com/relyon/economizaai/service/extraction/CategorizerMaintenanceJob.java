package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.service.admin.AdminProductService;
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

    @Value("${economizaai.categorizer.maintenance.enabled:true}")
    private boolean enabled;

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
    }
}
