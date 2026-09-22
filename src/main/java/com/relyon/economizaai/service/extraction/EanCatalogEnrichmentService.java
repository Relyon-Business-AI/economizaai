package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fills catalog gaps on demand: for barcodes our imported catalog can't yet
 * categorize, it queries the live OFF-family API ({@link OpenFoodFactsApiClient})
 * and caches-through the result, so the next lookup (and every future receipt
 * with that product) resolves locally.
 *
 * <p>Called from the UNTRANSACTED ingestion phase ({@code ReceiptIngestionService},
 * after parse, before persist) — the HTTP calls must never run inside a DB
 * transaction, and must never break ingest, so this is strictly best-effort.
 *
 * <p><b>Latency:</b> lookups run with bounded concurrency ({@link #FETCH_CONCURRENCY})
 * so a 20-new-item receipt costs ~a few seconds, not ~20. A scanned receipt is
 * always enriched in full — there is no per-receipt lookup cap; the OFF API is
 * free, and concurrency (not a count cap) is what keeps us within fair-use.
 * <b>Waste:</b> every
 * attempted barcode is written back tagged {@link EanCatalogSource#LIVE_API} —
 * even when the API didn't know it or had no category — so it's marked "checked"
 * and never re-queried on a later purchase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EanCatalogEnrichmentService {

    /** Concurrent OFF calls per receipt — bounded to stay within OFF fair-use. */
    private static final int FETCH_CONCURRENCY = 6;

    private final OpenFoodFactsApiClient apiClient;
    private final EanCatalogService eanCatalogService;

    private final ExecutorService fetchPool = Executors.newFixedThreadPool(FETCH_CONCURRENCY, runnable -> {
        var thread = new Thread(runnable, "ean-enrich");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdown() {
        fetchPool.shutdownNow();
    }

    public boolean isEnabled() {
        return apiClient.isEnabled();
    }

    /**
     * For each distinct EAN we can't yet categorize — missing, or present with a
     * null category that hasn't been live-checked — fetch it live (concurrently)
     * and upsert. Barcodes the API can't help with are still written as a
     * LIVE_API "checked" marker so they aren't re-queried. Returns rows written.
     */
    public int enrichMissing(Collection<String> eans) {
        if (!apiClient.isEnabled() || eans == null || eans.isEmpty()) return 0;
        var toCheck = eans.stream()
                .filter(ean -> ean != null && !ean.isBlank())
                .distinct()
                .filter(this::needsLiveCheck)
                .toList();
        if (toCheck.isEmpty()) return 0;

        // Fetch concurrently; a miss becomes a data-less row so the barcode is
        // recorded as checked (and thus skipped next time).
        var rows = toCheck.stream()
                .map(ean -> CompletableFuture.supplyAsync(
                        () -> apiClient.fetch(ean).orElseGet(() -> new OpenFoodFactsRow(ean, null, null, null)),
                        fetchPool))
                .toList()
                .stream()
                .map(CompletableFuture::join)
                .toList();

        var outcome = eanCatalogService.bulkImportOpenFoodFacts(rows, EanCatalogSource.LIVE_API);
        var found = rows.stream().filter(row -> row.productName() != null || row.categoryTags() != null).count();
        log.info("ean_catalog.enrich checked={} found={} written={}",
                toCheck.size(), found, outcome.imported());
        return outcome.imported();
    }

    /**
     * True when we should query the live API: the catalog has no row, OR a row
     * with a null category that we haven't already live-checked (an old import
     * that predates the pt: tag mapping). A row already sourced LIVE_API is left
     * alone even if uncategorized — the API had nothing, so re-asking is waste.
     */
    private boolean needsLiveCheck(String ean) {
        var existing = eanCatalogService.lookup(ean);
        if (existing.isEmpty()) return true;
        var entry = existing.get();
        return entry.getCategory() == null && entry.getSource() != EanCatalogSource.LIVE_API;
    }
}
