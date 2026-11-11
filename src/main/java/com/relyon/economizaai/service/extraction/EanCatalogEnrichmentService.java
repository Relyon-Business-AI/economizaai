package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import com.relyon.economizaai.service.extraction.OpenFoodFactsApiClient.LookupResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

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
 * <p><b>Latency:</b> the warm-up runs ON the ingest thread, so it is bounded twice:
 * a wall-clock budget for the whole receipt ({@code warmup-budget-ms}) and a cap on
 * how many unseen barcodes one receipt may look up ({@code warmup-max-eans}), with
 * bounded concurrency ({@link #FETCH_CONCURRENCY}) inside. Whatever doesn't fit is
 * simply left unchecked and picked up by a later receipt carrying the same barcode.
 * <b>Waste:</b> a barcode the API really doesn't know is written back tagged
 * {@link EanCatalogSource#LIVE_API} as a data-less "checked" marker, so it's never
 * re-queried. A lookup that could NOT be answered (timeout, 429, 5xx, budget ran
 * out) writes nothing — storing it as a miss would hide the product forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EanCatalogEnrichmentService {

    /** Concurrent OFF calls per receipt — bounded to stay within OFF fair-use. */
    private static final int FETCH_CONCURRENCY = 6;
    private static final int UNLIMITED = Integer.MAX_VALUE;
    private static final long NO_BUDGET = 0L;

    private final OpenFoodFactsApiClient apiClient;
    private final EanCatalogService eanCatalogService;

    /** Field-injected (not constructor args) so Lombok's @RequiredArgsConstructor is untouched. */
    @Value("${economizaai.ingestion.ean-catalog.warmup-budget-ms:5000}")
    private long warmupBudgetMs = 5000;

    @Value("${economizaai.ingestion.ean-catalog.warmup-max-eans:15}")
    private int warmupMaxEans = 15;

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
     * Ingest-time warm-up: for the distinct EANs we can't yet categorize — missing, or present
     * with a null category that hasn't been live-checked — fetch them live (concurrently) and
     * upsert, within the per-receipt cap and time budget. Returns rows written.
     */
    public int enrichMissing(Collection<String> eans) {
        return enrich(eans, true, Math.max(1, warmupMaxEans), Math.max(0, warmupBudgetMs));
    }

    /**
     * Re-fetch the given EANs from OFF REGARDLESS of whether they were already
     * checked — used by the taxonomy re-categorization sweep to backfill the new
     * fine node (and the extra OFF metadata) onto catalog rows imported before
     * those columns existed. Heavier than {@link #enrichMissing} (no skip, no cap,
     * no time budget), so run it deliberately (admin-triggered), not on the ingest hot path.
     */
    public int enrichForce(Collection<String> eans) {
        return enrich(eans, false, UNLIMITED, NO_BUDGET);
    }

    private int enrich(Collection<String> eans, boolean skipAlreadyChecked, int maxEans, long budgetMs) {
        if (!apiClient.isEnabled() || eans == null || eans.isEmpty()) return 0;
        var candidates = eans.stream()
                .filter(ean -> ean != null && !ean.isBlank())
                .distinct();
        var toCheck = (skipAlreadyChecked ? candidates.filter(this::needsLiveCheck) : candidates)
                .limit(maxEans)
                .toList();
        if (toCheck.isEmpty()) return 0;

        var budgetExpired = budgetClock(budgetMs);
        var lookups = toCheck.stream()
                .map(ean -> CompletableFuture.supplyAsync(() -> lookUpUnlessExpired(ean, budgetExpired), fetchPool))
                .toList();
        awaitLookups(lookups, budgetMs);

        var rows = rowsWorthRemembering(toCheck, lookups);
        var unanswered = toCheck.size() - rows.size();
        if (rows.isEmpty()) {
            log.info("ean_catalog.enrich checked={} found=0 written=0 unanswered={}", toCheck.size(), unanswered);
            return 0;
        }
        var outcome = eanCatalogService.bulkImportOpenFoodFacts(rows, EanCatalogSource.LIVE_API);
        var found = rows.stream().filter(row -> row.productName() != null || row.categoryTags() != null).count();
        log.info("ean_catalog.enrich checked={} found={} written={} unanswered={}",
                toCheck.size(), found, outcome.imported(), unanswered);
        return outcome.imported();
    }

    /** Says whether the wall-clock budget is gone; a budget of zero never expires. */
    private static BooleanSupplier budgetClock(long budgetMs) {
        if (budgetMs <= NO_BUDGET) return () -> false;
        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        return () -> System.nanoTime() - deadlineNanos >= 0;
    }

    /** A lookup still waiting in the pool queue when the budget ends is dropped without a call. */
    private LookupResult lookUpUnlessExpired(String ean, BooleanSupplier budgetExpired) {
        if (budgetExpired.getAsBoolean()) return LookupResult.unavailable();
        return apiClient.lookup(ean, budgetExpired);
    }

    /** Waits for the lookups, but never past the budget — late answers are simply not used. */
    private static void awaitLookups(List<CompletableFuture<LookupResult>> lookups, long budgetMs) {
        var all = CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new));
        try {
            if (budgetMs <= NO_BUDGET) {
                all.get();
            } else {
                all.get(budgetMs, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException budgetExhausted) {
            log.info("ean_catalog.enrich.budget_exhausted budgetMs={} lookups={}", budgetMs, lookups.size());
        } catch (ExecutionException lookupFailed) {
            log.debug("ean_catalog.enrich.lookup_failed reason={}", lookupFailed.getCause().getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Rows to write back: the products found, plus a data-less "checked" marker for barcodes
     * the API really answered "not found". Unanswered lookups (still running, failed, API
     * unavailable) produce no row, so they are asked again on a later receipt.
     */
    private static List<OpenFoodFactsRow> rowsWorthRemembering(List<String> eans,
                                                               List<CompletableFuture<LookupResult>> lookups) {
        var rows = new ArrayList<OpenFoodFactsRow>();
        for (var index = 0; index < eans.size(); index++) {
            var lookup = lookups.get(index);
            if (!lookup.isDone() || lookup.isCompletedExceptionally()) continue;
            var result = lookup.getNow(null);
            if (result == null) continue;
            switch (result.status()) {
                case FOUND -> rows.add(result.row());
                case NOT_FOUND -> rows.add(new OpenFoodFactsRow(eans.get(index), null, null, null));
                case UNAVAILABLE -> { }
            }
        }
        return rows;
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
