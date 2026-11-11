package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.EanCatalogService.BulkImportOutcome;
import com.relyon.economizaai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import com.relyon.economizaai.service.extraction.OpenFoodFactsApiClient.LookupResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        verify(apiClient, never()).lookup(any(), any());
        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList(), any());
    }

    @Test
    void enrichMissing_skipsEansAlreadyCategorized() {
        when(apiClient.isEnabled()).thenReturn(true);
        var categorized = mock(EanCatalogEntry.class);
        when(categorized.getCategory()).thenReturn(ProductCategory.GROCERIES);
        when(catalogService.lookup("111")).thenReturn(Optional.of(categorized));

        assertEquals(0, service.enrichMissing(List.of("111")));

        verify(apiClient, never()).lookup(eq("111"), any());
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

        verify(apiClient, never()).lookup(eq("999"), any());
    }

    @Test
    void enrichMissing_reFetchesNullCategoryFromOldImport() {
        when(apiClient.isEnabled()).thenReturn(true);
        var oldImport = mock(EanCatalogEntry.class);
        when(oldImport.getCategory()).thenReturn(null);
        when(oldImport.getSource()).thenReturn(EanCatalogSource.OPEN_FOOD_FACTS);
        when(catalogService.lookup("neston")).thenReturn(Optional.of(oldImport));
        var row = new OpenFoodFactsRow("neston", "Neston", "Nestlé", "pt:cereais");
        when(apiClient.lookup(eq("neston"), any())).thenReturn(LookupResult.found(row));
        when(catalogService.bulkImportOpenFoodFacts(anyList(), eq(EanCatalogSource.LIVE_API)))
                .thenReturn(new BulkImportOutcome(1, 0));

        assertEquals(1, service.enrichMissing(List.of("neston")));

        verify(apiClient).lookup(eq("neston"), any());
    }

    @Test
    void enrichMissing_writesCheckedMarkerForBarcodesNotInOff() {
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        var found = new OpenFoodFactsRow("222", "Arroz", "Tio", "en:rice");
        when(apiClient.lookup(eq("222"), any())).thenReturn(LookupResult.found(found));
        when(apiClient.lookup(eq("333"), any())).thenReturn(LookupResult.notFound()); // unknown barcode
        when(catalogService.bulkImportOpenFoodFacts(anyList(), eq(EanCatalogSource.LIVE_API)))
                .thenReturn(new BulkImportOutcome(2, 0));

        service.enrichMissing(List.of("222", "333", "222")); // dupe collapsed

        verify(apiClient).lookup(eq("222"), any());
        verify(apiClient).lookup(eq("333"), any());
        // Both barcodes are written (333 as a data-less "checked" marker).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenFoodFactsRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogService).bulkImportOpenFoodFacts(captor.capture(), eq(EanCatalogSource.LIVE_API));
        var written = captor.getValue();
        assertEquals(2, written.size());
    }

    @Test
    void enrichMissing_apiUnavailable_storesNoPermanentMiss() {
        // A timeout / 429 / 5xx is "we couldn't ask", not "this barcode doesn't exist".
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        when(apiClient.lookup(eq("444"), any())).thenReturn(LookupResult.unavailable());

        assertEquals(0, service.enrichMissing(List.of("444")));

        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList(), any());
    }

    @Test
    void enrichMissing_writesOnlyTheAnsweredLookups() {
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        when(apiClient.lookup(eq("555"), any())).thenReturn(LookupResult.notFound());
        when(apiClient.lookup(eq("666"), any())).thenReturn(LookupResult.unavailable());
        when(catalogService.bulkImportOpenFoodFacts(anyList(), eq(EanCatalogSource.LIVE_API)))
                .thenReturn(new BulkImportOutcome(1, 0));

        service.enrichMissing(List.of("555", "666"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenFoodFactsRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogService).bulkImportOpenFoodFacts(captor.capture(), eq(EanCatalogSource.LIVE_API));
        assertEquals(List.of("555"), captor.getValue().stream().map(OpenFoodFactsRow::code).toList());
    }

    @Test
    void enrichMissing_looksUpAtMostTheConfiguredNumberOfUnseenEansPerReceipt() {
        ReflectionTestUtils.setField(service, "warmupMaxEans", 3);
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        when(apiClient.lookup(any(), any())).thenReturn(LookupResult.unavailable());
        var manyEans = IntStream.range(0, 20).mapToObj(index -> "78900000000" + index).toList();

        service.enrichMissing(manyEans);

        verify(apiClient, times(3)).lookup(any(), any());
    }

    @Test
    void enrichMissing_stopsWaitingWhenTheTimeBudgetEnds() throws InterruptedException {
        ReflectionTestUtils.setField(service, "warmupBudgetMs", 100L);
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        var releaseSlowApi = new CountDownLatch(1);
        when(apiClient.lookup(eq("777"), any())).thenAnswer(invocation -> {
            releaseSlowApi.await(10, TimeUnit.SECONDS); // an API that hangs well past the budget
            return LookupResult.notFound();
        });

        var startedAt = System.nanoTime();
        var written = service.enrichMissing(List.of("777"));
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        releaseSlowApi.countDown();

        assertEquals(0, written);
        assertTrue(elapsedMs < 3000, "the ingest thread must not wait for a hung lookup, waited " + elapsedMs + "ms");
        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList(), any());
    }

    @Test
    void enrichForce_hasNoCapAndIgnoresTheAlreadyCheckedSkip() {
        ReflectionTestUtils.setField(service, "warmupMaxEans", 1);
        when(apiClient.isEnabled()).thenReturn(true);
        when(apiClient.lookup(any(), any())).thenReturn(LookupResult.notFound());
        when(catalogService.bulkImportOpenFoodFacts(anyList(), eq(EanCatalogSource.LIVE_API)))
                .thenReturn(new BulkImportOutcome(2, 0));

        assertEquals(2, service.enrichForce(List.of("888", "999")));

        verify(apiClient, times(2)).lookup(any(), any());
        verify(catalogService, never()).lookup(any());
    }
}
