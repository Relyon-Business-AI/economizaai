package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.EanCatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EanCatalogServiceTest {

    @Mock private EanCatalogRepository eanCatalogRepository;
    @InjectMocks private EanCatalogService service;

    @Test
    void lookup_returnsEntryWhenEanFound() {
        var entry = EanCatalogEntry.builder()
                .ean("7894900010015").category(ProductCategory.BEVERAGES).build();
        when(eanCatalogRepository.findByEan("7894900010015")).thenReturn(Optional.of(entry));

        var result = service.lookup("7894900010015");

        assertTrue(result.isPresent());
        assertEquals(ProductCategory.BEVERAGES, result.get().getCategory());
    }

    @Test
    void lookup_returnsEmptyWhenEanNotFound() {
        when(eanCatalogRepository.findByEan("9999999999999")).thenReturn(Optional.empty());

        var result = service.lookup("9999999999999");

        assertFalse(result.isPresent());
    }

    @Test
    void bulkImport_insertsNewEntries() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(2L);

        var entries = List.of(
                new EanCatalogService.EanImportRequest("7894900010015", "Coca-Cola", "Coca-Cola",
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS),
                new EanCatalogService.EanImportRequest("7891000455296", "KitKat", "Nestlé",
                        ProductCategory.GROCERIES, EanCatalogSource.OPEN_FOOD_FACTS)
        );

        var outcome = service.bulkImport(entries);

        assertEquals(2, outcome.imported());
        assertEquals(0, outcome.skipped());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals("7894900010015", captor.getValue().get(0).getEan());
    }

    @Test
    void bulkImport_updatesExistingEntry() {
        var existing = EanCatalogEntry.builder()
                .ean("7894900010015").category(ProductCategory.OTHER).build();
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of(existing));
        when(eanCatalogRepository.count()).thenReturn(1L);

        service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest("7894900010015", "Coca-Cola", "Coca-Cola",
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS)
        ));

        assertEquals(ProductCategory.BEVERAGES, existing.getCategory());
        assertEquals("Coca-Cola", existing.getGenericName());
    }

    @Test
    void bulkImportOpenFoodFacts_mapsCategoryTagsServerSide() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(2L);

        var outcome = service.bulkImportOpenFoodFacts(List.of(
                new EanCatalogService.OpenFoodFactsRow("7894900010015", "Coca-Cola 2L",
                        "Coca-Cola", "en:beverages,en:sodas,pt:refrigerantes"),
                new EanCatalogService.OpenFoodFactsRow("7891000100103", "Leite Moça",
                        "Nestlé,Moça", "en:dairies,en:sweetened-condensed-milks")));

        assertEquals(2, outcome.imported());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        var saved = captor.getValue();
        assertEquals(ProductCategory.BEVERAGES, saved.get(0).getCategory());
        assertEquals("Coca-Cola", saved.get(0).getBrand());
        assertEquals(ProductCategory.MEAT_DAIRY, saved.get(1).getCategory());
        assertEquals("Nestlé", saved.get(1).getBrand(), "first brand of a comma list wins");
    }

    @Test
    void bulkImportOpenFoodFacts_truncatesOversizedNameAndBrand() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(1L);
        var longName = "X".repeat(250);
        var longBrand = "Y".repeat(150);

        service.bulkImportOpenFoodFacts(List.of(
                new EanCatalogService.OpenFoodFactsRow("7891234567890", longName, longBrand, "en:beverages")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        var saved = captor.getValue().get(0);
        assertEquals(100, saved.getGenericName().length(), "name truncated to column limit");
        assertEquals(100, saved.getBrand().length(), "brand truncated to column limit");
    }

    @Test
    void bulkImport_skipsEanLongerThan14Digits() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(1L);

        var outcome = service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest("123456789012345678", "Junk", null,
                        ProductCategory.GROCERIES, EanCatalogSource.OPEN_FOOD_FACTS),
                new EanCatalogService.EanImportRequest("7891234567890", "Valid", null,
                        ProductCategory.GROCERIES, EanCatalogSource.OPEN_FOOD_FACTS)));

        assertEquals(1, outcome.imported());
        assertEquals(1, outcome.skipped());
    }

    @Test
    void bulkImport_truncatesOversizedNameAndBrandToColumnLimit() {
        // Direct curated import (POST /ean-catalog/import) forwards raw request
        // fields. An oversized genericName/brand on an EXISTING entry produces the
        // prod UPDATE that overflows varchar(100):
        //   value too long for type character varying(100) [update ean_catalog set brand=?,generic_name=?...]
        var existing = EanCatalogEntry.builder()
                .ean("7894900010015").category(ProductCategory.OTHER).build();
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of(existing));
        when(eanCatalogRepository.count()).thenReturn(1L);
        var longName = "X".repeat(250);
        var longBrand = "Y".repeat(150);

        service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest("7894900010015", longName, longBrand,
                        ProductCategory.BEVERAGES, EanCatalogSource.CURATED_IMPORT)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        var saved = captor.getValue().get(0);
        assertEquals(100, saved.getGenericName().length(), "name truncated to column limit");
        assertEquals(100, saved.getBrand().length(), "brand truncated to column limit");
    }

    @Test
    void bulkImport_dedupesDuplicateEanWithinBatch() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(1L);

        var outcome = service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest("7891234567890", "First", null,
                        ProductCategory.GROCERIES, EanCatalogSource.OPEN_FOOD_FACTS),
                new EanCatalogService.EanImportRequest("7891234567890", "Second", "Nestlé",
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS)));

        assertEquals(1, outcome.imported(), "same EAN collapses to one entry");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("Second", captor.getValue().get(0).getGenericName(), "last write wins");
    }

    @Test
    void bulkImport_skipsEntriesWithNullOrBlankEan() {
        // All EANs invalid → the eans list is empty, so findByEanIn is never queried.
        when(eanCatalogRepository.count()).thenReturn(0L);

        var outcome = service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest(null, "Produto", null, ProductCategory.GROCERIES, null),
                new EanCatalogService.EanImportRequest("  ", "Outro", null, ProductCategory.GROCERIES, null)
        ));

        assertEquals(0, outcome.imported());
        assertEquals(2, outcome.skipped());
        verify(eanCatalogRepository, never()).findByEanIn(any());
    }

    @Test
    void bulkImport_skipsEntriesWithEanLongerThanColumn() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(1L);

        var outcome = service.bulkImport(List.of(
                // 15 chars — overflows ean varchar(14), triggering the DataIntegrityViolationException in prod
                new EanCatalogService.EanImportRequest("789490001001599", "Bad Code", null,
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS),
                new EanCatalogService.EanImportRequest("7894900010015", "Coca-Cola", "Coca-Cola",
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS)
        ));

        assertEquals(1, outcome.imported());
        assertEquals(1, outcome.skipped());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("7894900010015", captor.getValue().get(0).getEan());
    }

    @Test
    void bulkImport_returnsZeroOnEmptyList() {
        var outcome = service.bulkImport(List.of());

        assertEquals(0, outcome.imported());
        assertEquals(0, outcome.skipped());
    }

    @Test
    void parseOpenFoodFactsRow_mapsBeverages() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "7894900010015", "Coca-Cola 350ml", "Coca-Cola", "en:beverages, en:sodas");

        assertEquals("7894900010015", req.ean());
        assertEquals("Coca-Cola 350ml", req.genericName());
        assertEquals("Coca-Cola", req.brand());
        assertEquals(ProductCategory.BEVERAGES, req.category());
        assertEquals(EanCatalogSource.OPEN_FOOD_FACTS, req.source());
    }

    @Test
    void parseOpenFoodFactsRow_mapsGroceriesForCereals() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "123", "Granola", "Naturovos", "en:cereals-and-potatoes, en:cereals");

        assertEquals(ProductCategory.GROCERIES, req.category());
    }

    @Test
    void parseOpenFoodFactsRow_returnsNullCategoryForUnknownTags() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "123", "Produto Desconhecido", null, "xx:unknown-tag");

        assertNull(req.category());
    }

    @Test
    void parseOpenFoodFactsRow_handlesNullCategoryTags() {
        var req = EanCatalogService.parseOpenFoodFactsRow("123", "Nome", "Marca", null);

        assertNull(req.category());
    }

    @Test
    void parseOpenFoodFactsRow_takesFirstBrandFromCommaList() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "123", "Produto", "Marca Principal, Marca Secundária", "en:beverages");

        assertEquals("Marca Principal", req.brand());
    }

    @Test
    void parseOpenFoodFactsRow_commaOnlyBrandDoesNotThrow() {
        // OFF occasionally ships brands="," (comma only). split(",") returns [] in Java
        // (trailing empty strings discarded) → ArrayIndexOutOfBoundsException: Index 0 out of
        // bounds for length 0 at EanCatalogService.firstBrand — reproduces the prod error.
        var req = EanCatalogService.parseOpenFoodFactsRow("7891234567890", "Produto", ",", "en:beverages");

        assertNull(req.brand(), "comma-only brands string should yield null brand, not throw");
    }

    @Test
    void parseOpenFoodFactsRow_leadingCommaYieldsSecondBrandNotBlank() {
        // brands=",Nestlé" → split(",", -1)[0] is "" (blank) → null; not the secondary brand.
        // Guard: we only take the first element regardless; blank first = null.
        var req = EanCatalogService.parseOpenFoodFactsRow("7891234567890", "Produto", ",Nestlé", "en:beverages");

        assertNull(req.brand(), "leading-comma brand string (blank first token) should yield null");
    }
}
