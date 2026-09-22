package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.EanCatalogService.BulkImportOutcome;
import com.relyon.economizaai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EanCatalogEnrichmentServiceTest {

    private final OpenFoodFactsApiClient apiClient = mock(OpenFoodFactsApiClient.class);
    private final EanCatalogService catalogService = mock(EanCatalogService.class);
    private final EanCatalogEnrichmentService service =
            new EanCatalogEnrichmentService(apiClient, catalogService);

    @Test
    void enrichMissing_noopWhenDisabled() {
        when(apiClient.isEnabled()).thenReturn(false);

        assertEquals(0, service.enrichMissing(List.of("7891000098950")));

        verify(apiClient, never()).fetch(any());
        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList(), any());
    }

    @Test
    void enrichMissing_skipsEansAlreadyCategorized() {
        when(apiClient.isEnabled()).thenReturn(true);
        var categorized = mock(EanCatalogEntry.class);
        when(categorized.getCategory()).thenReturn(ProductCategory.GROCERIES);
        when(catalogService.lookup("111")).thenReturn(Optional.of(categorized));

        assertEquals(0, service.enrichMissing(List.of("111")));

        verify(apiClient, never()).fetch("111");
        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList(), any());
    }

    @Test
    void enrichMissing_skipsRowsAlreadyLiveChecked() {
        // A row the live API already checked (and couldn't categorize) is left alone.
        when(apiClient.isEnabled()).thenReturn(true);
        var checked = mock(EanCatalogEntry.class);
        when(checked.getCategory()).thenReturn(null);
        when(checked.getSource()).thenReturn(EanCatalogSource.LIVE_API);
        when(catalogService.lookup("999")).thenReturn(Optional.of(checked));

        assertEquals(0, service.enrichMissing(List.of("999")));

        verify(apiClient, never()).fetch("999");
    }

    @Test
    void enrichMissing_reFetchesNullCategoryFromOldImport() {
        when(apiClient.isEnabled()).thenReturn(true);
        var oldImport = mock(EanCatalogEntry.class);
        when(oldImport.getCategory()).thenReturn(null);
        when(oldImport.getSource()).thenReturn(EanCatalogSource.OPEN_FOOD_FACTS);
        when(catalogService.lookup("neston")).thenReturn(Optional.of(oldImport));
        var row = new OpenFoodFactsRow("neston", "Neston", "Nestlé", "pt:cereais");
        when(apiClient.fetch("neston")).thenReturn(Optional.of(row));
        when(catalogService.bulkImportOpenFoodFacts(anyList(), eq(EanCatalogSource.LIVE_API)))
                .thenReturn(new BulkImportOutcome(1, 0));

        assertEquals(1, service.enrichMissing(List.of("neston")));

        verify(apiClient).fetch("neston");
    }

    @Test
    void enrichMissing_writesCheckedMarkerForBarcodesNotInOff() {
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        var found = new OpenFoodFactsRow("222", "Arroz", "Tio", "en:rice");
        when(apiClient.fetch("222")).thenReturn(Optional.of(found));
        when(apiClient.fetch("333")).thenReturn(Optional.empty()); // unknown barcode
        when(catalogService.bulkImportOpenFoodFacts(anyList(), eq(EanCatalogSource.LIVE_API)))
                .thenReturn(new BulkImportOutcome(2, 0));

        service.enrichMissing(List.of("222", "333", "222")); // dupe collapsed

        verify(apiClient).fetch("222");
        verify(apiClient).fetch("333");
        // Both barcodes are written (333 as a data-less "checked" marker).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenFoodFactsRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogService).bulkImportOpenFoodFacts(captor.capture(), eq(EanCatalogSource.LIVE_API));
        var written = captor.getValue();
        assertEquals(2, written.size());
    }
}
