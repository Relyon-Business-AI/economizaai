package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.EanCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages the EAN product catalog — a local DB table seeded from Open Food
 * Facts (or admin imports) that maps GTIN/EAN barcodes to product categories.
 *
 * <p>This is queried in the canonicalization cascade as step A2: after we
 * confirm an EAN isn't already in our own products table, we check here
 * before falling through to the keyword dictionary + ML.</p>
 *
 * <p><b>Seeding:</b> download the Open Food Facts Brazil CSV dump and call
 * {@link #bulkImport} with the parsed rows. The mapper
 * {@link OpenFoodFactsCategoryMapper} converts OPF category tags to our enum.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EanCatalogService {

    /** Matches the {@code ean} column length ({@code varchar(14)}) in {@link EanCatalogEntry}. */
    private static final int MAX_EAN_LENGTH = 14;
    /** IN-clause chunk size — keeps the existing-row lookup under Postgres's bind-param limit. */
    private static final int LOOKUP_CHUNK = 1000;

    private final EanCatalogRepository eanCatalogRepository;

    private static boolean isImportableEan(String ean) {
        return ean != null && !ean.isBlank() && ean.length() <= MAX_EAN_LENGTH;
    }

    /** Step A2 in the cascade — O(1) primary-key lookup. */
    public Optional<EanCatalogEntry> lookup(String ean) {
        return eanCatalogRepository.findByEan(ean);
    }

    /**
     * Bulk-upserts EAN entries into the catalog.  Existing EANs are updated
     * (category/genericName/brand may improve with better data sources);
     * new ones are inserted.  Rows with null or blank EAN are skipped.
     */
    @Transactional
    public BulkImportOutcome bulkImport(List<EanImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);

        var eans = entries.stream()
                .filter(req -> isImportableEan(req.ean()))
                .map(EanImportRequest::ean)
                .distinct()
                .toList();

        // Look up existing rows in chunks — a single IN (...) with tens of thousands
        // of EANs would blow Postgres's ~65535 bind-parameter limit.
        var existingByEan = new java.util.HashMap<String, EanCatalogEntry>();
        for (var start = 0; start < eans.size(); start += LOOKUP_CHUNK) {
            var chunk = eans.subList(start, Math.min(start + LOOKUP_CHUNK, eans.size()));
            for (var found : eanCatalogRepository.findByEanIn(chunk)) {
                existingByEan.put(found.getEan(), found);
            }
        }

        // Keyed by EAN so a duplicate code within the same batch (OFF repeats them)
        // updates the same entry instead of inserting a second row → no unique violation.
        var toSaveByEan = new java.util.LinkedHashMap<String, EanCatalogEntry>();
        var skipped = 0;

        for (var req : entries) {
            // GTIN is at most 14 chars; longer "codes" in OFF are junk that would
            // overflow the ean column. Skip rather than fail the whole batch.
            if (!isImportableEan(req.ean())) {
                skipped++;
                continue;
            }
            var entry = toSaveByEan.computeIfAbsent(req.ean(), ean ->
                    existingByEan.getOrDefault(ean, EanCatalogEntry.builder().ean(ean).build()));
            // Truncate to column limits: direct/curated imports forward raw request
            // text, which routinely overflows varchar(100) and fails the UPDATE at
            // flush (the OFF path already truncates in parseOpenFoodFactsRow).
            if (req.genericName() != null) entry.setGenericName(truncate(req.genericName(), NAME_MAX));
            if (req.brand() != null) entry.setBrand(truncate(req.brand(), BRAND_MAX));
            if (req.category() != null) entry.setCategory(req.category());
            entry.setSource(req.source() != null ? req.source() : EanCatalogSource.CURATED_IMPORT);
        }
        var toSave = new ArrayList<>(toSaveByEan.values());

        eanCatalogRepository.saveAll(toSave);
        log.info("ean_catalog.bulk_import imported={} skipped={} total={}",
                toSave.size(), skipped, eanCatalogRepository.count());
        return new BulkImportOutcome(toSave.size(), skipped);
    }

    /**
     * Bulk-imports raw Open Food Facts rows, mapping OPF category tags to our
     * enum server-side (via {@link OpenFoodFactsCategoryMapper}) so the import
     * client stays dumb — it just forwards what the OFF API returns. Rows with
     * no EAN are skipped.
     */
    @Transactional
    public BulkImportOutcome bulkImportOpenFoodFacts(List<OpenFoodFactsRow> rows) {
        return bulkImportOpenFoodFacts(rows, EanCatalogSource.OPEN_FOOD_FACTS);
    }

    /** As {@link #bulkImportOpenFoodFacts(List)} but tags rows with the given source
     *  (e.g. {@code LIVE_API} for on-demand fallback fetches). */
    @Transactional
    public BulkImportOutcome bulkImportOpenFoodFacts(List<OpenFoodFactsRow> rows, EanCatalogSource source) {
        if (rows == null || rows.isEmpty()) return new BulkImportOutcome(0, 0);
        var mapped = rows.stream()
                .map(row -> parseOpenFoodFactsRow(row.code(), row.productName(), row.brands(), row.categoryTags(), source))
                .toList();
        return bulkImport(mapped);
    }

    /**
     * Parses a single Open Food Facts CSV row (header fields expected:
     * {@code code,product_name,brands,categories_tags}) and imports it.
     * Intended for batch use — caller collects rows and calls
     * {@link #bulkImport} with the full list for performance.
     */
    // Column limits (see EanCatalogEntry): free-form OFF text routinely exceeds
    // them, so truncate here rather than let the whole batch fail on a constraint.
    private static final int NAME_MAX = 100;
    private static final int BRAND_MAX = 100;

    public static EanImportRequest parseOpenFoodFactsRow(String ean, String productName,
                                                          String brands, String categoryTags) {
        return parseOpenFoodFactsRow(ean, productName, brands, categoryTags, EanCatalogSource.OPEN_FOOD_FACTS);
    }

    public static EanImportRequest parseOpenFoodFactsRow(String ean, String productName,
                                                          String brands, String categoryTags,
                                                          EanCatalogSource source) {
        var category = OpenFoodFactsCategoryMapper.map(categoryTags);
        return new EanImportRequest(
                ean,
                truncate(clean(productName), NAME_MAX),
                truncate(firstBrand(brands), BRAND_MAX),
                category == ProductCategory.OTHER ? null : category,  // null = unknown, not forced OTHER
                source
        );
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String firstBrand(String brands) {
        if (brands == null || brands.isBlank()) return null;
        var first = brands.split(",", -1)[0].trim();
        return first.isBlank() ? null : first;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Raw Open Food Facts row as returned by its search API. */
    public record OpenFoodFactsRow(String code, String productName, String brands, String categoryTags) {}

    public record EanImportRequest(
            String ean,
            String genericName,
            String brand,
            ProductCategory category,
            EanCatalogSource source) {}

    public record BulkImportOutcome(int imported, int skipped) {}
}
