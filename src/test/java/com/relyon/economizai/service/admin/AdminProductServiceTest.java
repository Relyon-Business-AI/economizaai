package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.MergeProductRequest;
import com.relyon.economizai.dto.request.SetProductBrandRequest;
import com.relyon.economizai.exception.InvalidProductDeletionException;
import com.relyon.economizai.exception.InvalidProductMergeException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.extraction.BrandExtractor;
import com.relyon.economizai.service.extraction.ProductExtraction;
import com.relyon.economizai.service.extraction.ProductExtractor;
import com.relyon.economizai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ShoppingListItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private HouseholdProductAliasRepository householdProductAliasRepository;
    @Mock private ConsumptionSnoozeRepository consumptionSnoozeRepository;
    @Mock private ProductExtractor productExtractor;
    @Mock private BrandExtractor brandExtractor;

    @InjectMocks private AdminProductService service;

    private Product product(String name, ProductCategory current, CategorizationSource source) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(current).categorizationSource(source).build();
    }

    private ProductExtraction extracted(ProductCategory category, CategorizationSource source) {
        return new ProductExtraction(null, null, null, null, category, source);
    }

    @Test
    void backfillBrands_fillsMissingOnlyNeverOverwrites() {
        var missing = Product.builder().id(UUID.randomUUID()).normalizedName("AZ ARG D'AGUIRRE EV").build();
        var hasBrand = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ TIO J").brand("Tio João").build();
        var noMatch = Product.builder().id(UUID.randomUUID()).normalizedName("XYZ GENERICO").build();
        when(productRepository.findAll()).thenReturn(List.of(missing, hasBrand, noMatch));
        when(brandExtractor.find("AZ ARG D'AGUIRRE EV")).thenReturn("D'Aguirre");
        when(brandExtractor.find("XYZ GENERICO")).thenReturn(null);

        var result = service.backfillBrands();

        assertEquals(1, result.filled());
        assertEquals(1, result.stillMissing());
        assertEquals("D'Aguirre", missing.getBrand());
        assertEquals("Tio João", hasBrand.getBrand(), "existing brand not overwritten");
    }

    @Test
    void brandCoverageReport_measuresWithoutPersisting() {
        var missing = Product.builder().id(UUID.randomUUID()).normalizedName("AZ ARG D'AGUIRRE EV").build();
        var hasBrand = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ TIO J").brand("Tio João").build();
        var noMatch = Product.builder().id(UUID.randomUUID()).normalizedName("XYZ GENERICO").build();
        when(productRepository.findAll()).thenReturn(List.of(missing, hasBrand, noMatch));
        when(brandExtractor.find("AZ ARG D'AGUIRRE EV")).thenReturn("D'Aguirre");
        when(brandExtractor.find("XYZ GENERICO")).thenReturn(null);

        var report = service.brandCoverageReport();

        assertEquals(3, report.totalProducts());
        assertEquals(1, report.alreadyHaveBrand());
        assertEquals(1, report.wouldFillNow());
        assertEquals(1, report.stillNoBrand());
        assertEquals(66.7, report.coveragePctAfter());
        assertEquals(1, report.sampleWouldFill().size());
        assertEquals("D'Aguirre", report.sampleWouldFill().get(0).brand());
        // dry-run: nothing written
        assertFalse(missing.getBrand() != null);
    }

    @Test
    void unmatchedReport_computesRateAndTopOffenders() {
        when(receiptItemRepository.countMatched()).thenReturn(80L);
        when(receiptItemRepository.countUnmatched()).thenReturn(20L);
        when(receiptItemRepository.topUnmatchedDescriptions(any()))
                .thenReturn(List.of(
                        new Object[]{"cebolinha mac", 7L},
                        new Object[]{"tempero verde", 4L}));

        var report = service.unmatchedReport(30);

        assertEquals(80, report.matchedItems());
        assertEquals(20, report.unmatchedItems());
        assertEquals(20.0, report.unmatchedPct());
        assertEquals("cebolinha mac", report.topUnmatched().get(0).description());
        assertEquals(7, report.topUnmatched().get(0).occurrences());
    }

    @Test
    void listAll_pagesThroughCatalog() {
        var product = product("ARROZ", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY);
        when(productRepository.findFiltered(null, null, null, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(product)));

        var page = service.listAll(PageRequest.of(0, 50));

        assertEquals(1, page.getTotalElements());
        assertEquals(ProductCategory.GROCERIES, page.getContent().get(0).category());
    }

    @Test
    void recategorizeReport_splitsDictionaryVsMlAndFlagsUserOverrides() {
        var dictFix = product("LIMP VEJA", null, CategorizationSource.NONE);              // dict suggestion
        var mlOnly = product("PRATO", null, CategorizationSource.NONE);                    // ML suggestion
        var userLocked = product("ABS", ProductCategory.PERSONAL_CARE, CategorizationSource.USER);
        var ok = product("ARROZ", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY);
        when(productRepository.findAll()).thenReturn(List.of(dictFix, mlOnly, userLocked, ok));
        when(productExtractor.extract("LIMP VEJA")).thenReturn(extracted(ProductCategory.CLEANING, CategorizationSource.DICTIONARY));
        when(productExtractor.extract("PRATO")).thenReturn(extracted(ProductCategory.BAKERY, CategorizationSource.ML));
        when(productExtractor.extract("ABS")).thenReturn(extracted(ProductCategory.BAKERY, CategorizationSource.ML));
        when(productExtractor.extract("ARROZ")).thenReturn(extracted(ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));

        var report = service.recategorizeReport();

        assertEquals(3, report.mismatchCount());
        assertEquals(1, report.applicableFromDictionary());
        assertEquals(1, report.mlSuggestions());
        assertEquals(1, report.skippedUserOverrides());
    }

    @Test
    void recategorizeApply_default_appliesOnlyDictionarySkipsMlUserAndNull() {
        var dictFix = product("LIMP VEJA", null, CategorizationSource.NONE);
        var mlOnly = product("PRATO", null, CategorizationSource.NONE);
        var userLocked = product("ABS", ProductCategory.PERSONAL_CARE, CategorizationSource.USER);
        var noSuggestion = product("XYZ", ProductCategory.BEVERAGES, CategorizationSource.ML);
        when(productRepository.findAll()).thenReturn(List.of(dictFix, mlOnly, userLocked, noSuggestion));
        when(productExtractor.extract("LIMP VEJA")).thenReturn(extracted(ProductCategory.CLEANING, CategorizationSource.DICTIONARY));
        when(productExtractor.extract("PRATO")).thenReturn(extracted(ProductCategory.BAKERY, CategorizationSource.ML));
        when(productExtractor.extract("ABS")).thenReturn(extracted(ProductCategory.BAKERY, CategorizationSource.ML));
        when(productExtractor.extract("XYZ")).thenReturn(extracted(null, CategorizationSource.NONE));

        var result = service.recategorizeApply(false);

        assertEquals(1, result.updated());
        assertEquals(1, result.skippedMl());
        assertEquals(1, result.skippedUserOverrides());
        assertEquals(1, result.unchanged());
        assertEquals(ProductCategory.CLEANING, dictFix.getCategory());
        assertEquals(CategorizationSource.DICTIONARY, dictFix.getCategorizationSource());
        assertEquals(null, mlOnly.getCategory(), "ML suggestion not applied by default");
        assertEquals(ProductCategory.PERSONAL_CARE, userLocked.getCategory(), "USER override untouched");
        assertEquals(ProductCategory.BEVERAGES, noSuggestion.getCategory(), "null suggestion not downgraded");
    }

    @Test
    void recategorizeApply_includeMl_alsoAppliesMlSuggestions() {
        var mlOnly = product("PRATO", null, CategorizationSource.NONE);
        when(productRepository.findAll()).thenReturn(List.of(mlOnly));
        when(productExtractor.extract("PRATO")).thenReturn(extracted(ProductCategory.BAKERY, CategorizationSource.ML));

        var result = service.recategorizeApply(true);

        assertEquals(1, result.updated());
        assertEquals(0, result.skippedMl());
        assertEquals(ProductCategory.BAKERY, mlOnly.getCategory());
    }

    @Test
    void listMissingBrandReturnsProductsWithSampleAliases() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        var alias1 = ProductAlias.builder().product(product)
                .rawDescription("ARROZ TIO J 5KG").normalizedDescription("arroz tio j 5kg").build();
        var alias2 = ProductAlias.builder().product(product)
                .rawDescription("ARROZ TIO JOAO 5KG").normalizedDescription("arroz tio joao 5kg").build();
        var pageable = PageRequest.of(0, 20);
        when(productRepository.findMissingBrand(pageable)).thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        when(aliasRepository.findByProductIdIn(List.of(product.getId()))).thenReturn(List.of(alias1, alias2));

        var page = service.listMissingBrand(pageable);

        assertEquals(1, page.getTotalElements());
        var response = page.getContent().get(0);
        assertEquals(product.getId(), response.id());
        assertEquals(2, response.sampleDescriptions().size());
        assertEquals("ARROZ TIO J 5KG", response.sampleDescriptions().get(0));
    }

    @Test
    void listMissingBrandReturnsEmptySamplesWhenNoAliases() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        var pageable = PageRequest.of(0, 20);
        when(productRepository.findMissingBrand(pageable)).thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        when(aliasRepository.findByProductIdIn(List.of(product.getId()))).thenReturn(List.of());

        var page = service.listMissingBrand(pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals(0, page.getContent().get(0).sampleDescriptions().size());
    }

    @Test
    void setBrandPersistsTrimmedBrandOnProduct() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.setBrand(product.getId(), new SetProductBrandRequest("  Tio João  "));

        assertEquals("Tio João", response.brand());
        verify(productRepository).save(product);
    }

    @Test
    void setBrandThrowsForUnknownProduct() {
        var unknownId = UUID.randomUUID();
        when(productRepository.findById(unknownId)).thenReturn(Optional.empty());
        var request = new SetProductBrandRequest("X");
        assertThrows(ProductNotFoundException.class,
                () -> service.setBrand(unknownId, request));
    }

    @Test
    void setCategory_setsCategoryAndLocksAsUser() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("racao dog chow")
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.DICTIONARY).build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.setCategory(product.getId(), ProductCategory.PET_SUPPLIES);

        assertEquals(ProductCategory.PET_SUPPLIES, response.category());
        assertEquals(CategorizationSource.USER, product.getCategorizationSource());
        verify(productRepository).save(product);
    }

    @Test
    void listDuplicateGroupsBucketsByMetadata() {
        var p1 = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz Tio Joao 5KG")
                .genericName("Arroz").brand("Tio Joao").packSize(new BigDecimal("5")).packUnit("KG").build();
        var p2 = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz Tio J 5KG")
                .genericName("Arroz").brand("Tio Joao").packSize(new BigDecimal("5")).packUnit("KG").build();
        var p3 = Product.builder().id(UUID.randomUUID()).normalizedName("Feijao Camil 1KG")
                .genericName("Feijao").brand("Camil").packSize(new BigDecimal("1")).packUnit("KG").build();
        var p4 = Product.builder().id(UUID.randomUUID()).normalizedName("Feijao Camil Carioca 1KG")
                .genericName("Feijao").brand("Camil").packSize(new BigDecimal("1")).packUnit("KG").build();
        when(productRepository.findDuplicateCandidates()).thenReturn(List.of(p1, p2, p3, p4));

        var groups = service.listDuplicateGroups();

        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).products().size());
        assertEquals(2, groups.get(1).products().size());
    }

    @Test
    void mergeRejectsSelfMerge() {
        var id = UUID.randomUUID();
        var request = new MergeProductRequest(id, false);
        assertThrows(InvalidProductMergeException.class,
                () -> service.merge(id, request));
    }

    @Test
    void mergeDryRunReturnsCountsWithoutApplyingChanges() {
        var survivor = Product.builder().id(UUID.randomUUID()).normalizedName("Survivor").build();
        var absorbed = Product.builder().id(UUID.randomUUID()).normalizedName("Absorbed").build();
        when(productRepository.findById(survivor.getId())).thenReturn(Optional.of(survivor));
        when(productRepository.findById(absorbed.getId())).thenReturn(Optional.of(absorbed));
        when(aliasRepository.countByProduct(absorbed)).thenReturn(3L);
        when(receiptItemRepository.countByProduct(absorbed)).thenReturn(0L);
        when(priceObservationRepository.countByProduct(absorbed)).thenReturn(7L);
        when(manualPurchaseRepository.countByProduct(absorbed)).thenReturn(0L);
        when(shoppingListItemRepository.countByProduct(absorbed)).thenReturn(1L);
        when(householdProductAliasRepository.countByProduct(absorbed)).thenReturn(2L);
        when(consumptionSnoozeRepository.countByProduct(absorbed)).thenReturn(0L);

        var result = service.merge(survivor.getId(),
                new MergeProductRequest(absorbed.getId(), true));

        assertTrue(result.dryRun());
        assertFalse(result.applied());
        assertEquals(3L, result.aliasesMigrated());
        assertEquals(7L, result.priceObservationsMigrated());
        verify(aliasRepository, never()).repointProduct(any(), any());
        verify(productRepository, never()).delete(any());
    }

    @Test
    void mergeAppliedRepointsAndDeletesAbsorbed() {
        var survivor = Product.builder().id(UUID.randomUUID()).normalizedName("Survivor").build();
        var absorbed = Product.builder().id(UUID.randomUUID()).normalizedName("Absorbed").build();
        when(productRepository.findById(survivor.getId())).thenReturn(Optional.of(survivor));
        when(productRepository.findById(absorbed.getId())).thenReturn(Optional.of(absorbed));
        when(receiptItemRepository.countByProduct(absorbed)).thenReturn(5L);
        when(aliasRepository.countByProduct(absorbed)).thenReturn(2L);
        when(priceObservationRepository.countByProduct(absorbed)).thenReturn(7L);
        when(manualPurchaseRepository.countByProduct(absorbed)).thenReturn(0L);
        when(shoppingListItemRepository.countByProduct(absorbed)).thenReturn(1L);
        when(householdProductAliasRepository.countByProduct(absorbed)).thenReturn(0L);
        when(consumptionSnoozeRepository.countByProduct(absorbed)).thenReturn(0L);
        when(aliasRepository.repointProduct(absorbed, survivor)).thenReturn(2);
        when(receiptItemRepository.repointProduct(absorbed, survivor)).thenReturn(5);
        when(priceObservationRepository.repointProduct(absorbed, survivor)).thenReturn(7);
        when(manualPurchaseRepository.repointProduct(absorbed, survivor)).thenReturn(0);
        when(shoppingListItemRepository.repointProduct(absorbed, survivor)).thenReturn(1);
        when(householdProductAliasRepository.deleteAbsorbedConflictsWithSurvivor(absorbed, survivor)).thenReturn(1);
        when(householdProductAliasRepository.repointProduct(absorbed, survivor)).thenReturn(0);
        when(consumptionSnoozeRepository.deleteAbsorbedConflictsWithSurvivor(absorbed, survivor)).thenReturn(0);
        when(consumptionSnoozeRepository.repointProduct(absorbed, survivor)).thenReturn(0);

        var result = service.merge(survivor.getId(),
                new MergeProductRequest(absorbed.getId(), false));

        assertFalse(result.dryRun());
        assertTrue(result.applied());
        assertEquals(2L, result.aliasesMigrated());
        assertEquals(5L, result.receiptItemsRepointed());
        assertEquals(7L, result.priceObservationsMigrated());
        assertEquals(1L, result.householdAliasesDroppedAsConflict());
        verify(productRepository).delete(absorbed);
    }

    @Test
    void delete_unreferenced_deletesAndReportsZeroDetached() {
        var target = product("E2E Canonical Product", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY);
        when(productRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(receiptItemRepository.countByProduct(target)).thenReturn(0L);

        var result = service.delete(target.getId(), false);

        assertEquals(target.getId(), result.productId());
        assertEquals(0L, result.receiptItemsDetached());
        verify(productRepository).delete(target);
    }

    @Test
    void delete_referenced_withoutForce_throwsAndKeepsProduct() {
        var target = product("Real Product", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY);
        when(productRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(receiptItemRepository.countByProduct(target)).thenReturn(4L);
        var targetId = target.getId();

        assertThrows(InvalidProductDeletionException.class, () -> service.delete(targetId, false));
        verify(productRepository, never()).delete(any());
    }

    @Test
    void delete_referenced_withForce_detachesAndDeletes() {
        var target = product("Real Product", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY);
        when(productRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(receiptItemRepository.countByProduct(target)).thenReturn(4L);

        var result = service.delete(target.getId(), true);

        assertEquals(4L, result.receiptItemsDetached());
        verify(productRepository).delete(target);
    }

    @Test
    void delete_unknown_throws() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ProductNotFoundException.class, () -> service.delete(id, false));
    }
}
