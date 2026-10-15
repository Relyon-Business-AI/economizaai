package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
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

    @Mock private TaxonomyRecategorizationService taxonomyRecategorizationService;
    @Mock private CategorizationQualityService categorizationQualityService;
    @Mock private CategorizerAdminService categorizerAdminService;
    @Mock private BrandAliasPromotionService brandAliasPromotionService;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private AutoPromotionService autoPromotionService;

    private CategorizerMaintenanceJob job(boolean enabled) {
        var j = new CategorizerMaintenanceJob(taxonomyRecategorizationService,
                categorizationQualityService, categorizerAdminService, brandAliasPromotionService,
                canonicalizationService, autoPromotionService);
        ReflectionTestUtils.setField(j, "enabled", enabled);
        return j;
    }

    private void stubAutoPromote() {
        when(autoPromotionService.promote())
                .thenReturn(new AutoPromotionService.PromotionOutcome(0, 0, 0, 0, 0));
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
    void maintain_whenEnabled_recategorizesAndSnapshots() {
        stubAutoPromote();
        when(taxonomyRecategorizationService.recategorizeIncremental()).thenReturn(new TaxonomyRecategorizationService.RecategorizeResult(10, 2, 0, 0, 0));
        stubBrandMaintenance();

        job(true).maintain();

        verify(taxonomyRecategorizationService).recategorizeIncremental();
        verify(categorizationQualityService).measureAndRecord(eq(CategorizationQualityTrigger.BACKFILL));
        verify(categorizerAdminService).deriveBrandsFromEanCatalog(0, true);
        verify(brandAliasPromotionService).promoteFromKnownBrands();
    }

    @Test
    void maintain_whenEnabled_runsAutoPromotion() {
        stubAutoPromote();
        when(taxonomyRecategorizationService.recategorizeIncremental()).thenReturn(new TaxonomyRecategorizationService.RecategorizeResult(10, 2, 0, 0, 0));
        stubBrandMaintenance();

        job(true).maintain();

        verify(autoPromotionService).promote();
    }

    @Test
    void maintain_whenDisabled_doesNothing() {
        job(false).maintain();

        verifyNoInteractions(taxonomyRecategorizationService, categorizationQualityService,
                categorizerAdminService, brandAliasPromotionService, autoPromotionService);
    }

    @Test
    void maintain_swallowsRecategorizeFailure_soSchedulerKeepsRunning() {
        stubAutoPromote();
        when(taxonomyRecategorizationService.recategorizeIncremental()).thenThrow(new RuntimeException("boom"));
        stubBrandMaintenance();

        job(true).maintain(); // must not throw

        verify(taxonomyRecategorizationService).recategorizeIncremental();
        // Brand maintenance is isolated — a recategorize failure must not block it.
        verify(categorizerAdminService).deriveBrandsFromEanCatalog(0, true);
    }

    @Test
    void maintain_swallowsAutoPromoteFailure_andContinues() {
        when(autoPromotionService.promote()).thenThrow(new RuntimeException("promote boom"));
        when(taxonomyRecategorizationService.recategorizeIncremental()).thenReturn(new TaxonomyRecategorizationService.RecategorizeResult(10, 2, 0, 0, 0));
        stubBrandMaintenance();

        job(true).maintain(); // must not throw

        // A failure in auto-promote must not block the rest of the maintenance.
        verify(taxonomyRecategorizationService).recategorizeIncremental();
    }

    @Test
    void maintain_swallowsBrandMaintenanceFailure_andContinues() {
        stubAutoPromote();
        when(taxonomyRecategorizationService.recategorizeIncremental()).thenReturn(new TaxonomyRecategorizationService.RecategorizeResult(10, 2, 0, 0, 0));
        when(categorizerAdminService.deriveBrandsFromEanCatalog(0, true))
                .thenThrow(new RuntimeException("brand boom"));

        job(true).maintain(); // must not throw

        verify(taxonomyRecategorizationService).recategorizeIncremental();
    }

    @Test
    void maintain_whenEnabled_callsUnmatchedRetry() {
        stubAutoPromote();
        when(taxonomyRecategorizationService.recategorizeIncremental()).thenReturn(new TaxonomyRecategorizationService.RecategorizeResult(10, 2, 0, 0, 0));
        stubBrandMaintenance();

        job(true).maintain();

        verify(canonicalizationService).retryUnmatched(0);
    }
}
