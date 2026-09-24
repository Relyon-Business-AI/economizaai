package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.RecategorizeResultResponse;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.service.admin.AdminProductService;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizerMaintenanceJobTest {

    @Mock private MlClassifierService mlClassifierService;
    @Mock private AdminProductService adminProductService;
    @Mock private CategorizationQualityService categorizationQualityService;
    @Mock private CategorizerAdminService categorizerAdminService;
    @Mock private BrandAliasPromotionService brandAliasPromotionService;
    @Mock private CanonicalizationService canonicalizationService;

    private CategorizerMaintenanceJob job(boolean enabled) {
        var j = new CategorizerMaintenanceJob(mlClassifierService, adminProductService,
                categorizationQualityService, categorizerAdminService, brandAliasPromotionService,
                canonicalizationService);
        ReflectionTestUtils.setField(j, "enabled", enabled);
        return j;
    }

    private void stubBrandMaintenance() {
        when(canonicalizationService.retryUnmatched(0))
                .thenReturn(new CanonicalizationService.RetryOutcome(0, 0));
        when(categorizerAdminService.deriveBrandsFromEanCatalog(0, true))
                .thenReturn(new CategorizerAdminService.BrandDerivationOutcome(0, 0, 0));
        when(brandAliasPromotionService.promoteFromKnownBrands())
                .thenReturn(new BrandAliasPromotionService.PromotionOutcome(0, 0));
    }

    @Test
    void maintain_whenEnabled_retrainsRecategorizesAndSnapshots() {
        when(adminProductService.recategorizeApply(false)).thenReturn(new RecategorizeResultResponse(10, 2, 0, 0, 8));
        stubBrandMaintenance();

        job(true).maintain();

        verify(mlClassifierService).retrain();
        verify(adminProductService).recategorizeApply(false);
        verify(categorizationQualityService).measureAndRecord(eq(CategorizationQualityTrigger.BACKFILL));
        verify(categorizerAdminService).deriveBrandsFromEanCatalog(0, true);
        verify(brandAliasPromotionService).promoteFromKnownBrands();
    }

    @Test
    void maintain_whenDisabled_doesNothing() {
        job(false).maintain();

        verifyNoInteractions(mlClassifierService, adminProductService, categorizationQualityService,
                categorizerAdminService, brandAliasPromotionService);
    }

    @Test
    void maintain_swallowsFailures_soTheSchedulerKeepsRunning() {
        when(mlClassifierService.retrain()).thenThrow(new RuntimeException("boom"));
        stubBrandMaintenance();

        job(true).maintain(); // must not throw

        verify(adminProductService, never()).recategorizeApply(false);
        // Brand maintenance is isolated — a retrain failure must not block it.
        verify(categorizerAdminService).deriveBrandsFromEanCatalog(0, true);
    }
}
