package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse.Failure;
import com.relyon.economizaai.dto.response.LiveAccuracyResponse;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures extraction quality against the golden set in the
 * categorization_benchmark_entries table (description → true
 * category/brand/quantity, grown at runtime via the admin import endpoint).
 * Runs the same cascade as ingestion and reports per-field accuracy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationBenchmarkService {

    private final ProductExtractor productExtractor;
    private final CategorizationBenchmarkEntryRepository benchmarkRepository;
    private final ProductRepository productRepository;

    /** Cap the mismatch sample so the response stays small even with many disagreements. */
    private static final int MISMATCH_SAMPLE_CAP = 50;

    /**
     * Live accuracy: instead of the fixed golden set, score the engine against REAL
     * human/community-validated products (source USER or CONSENSUS). For each, re-run
     * the deterministic engine on its description and compare to the human truth. The
     * number tracks the real incoming distribution; mismatches are genuine error cases.
     *
     * <p>Mild circularity caveat: if a curated/learned rule was created from the same
     * correction, that product will match — so this leans optimistic. It still surfaces
     * every case where the engine disagrees with a human, which is the useful signal.</p>
     */
    public LiveAccuracyResponse runLive() {
        var products = productRepository.findByCategorizationSourceIn(
                List.of(CategorizationSource.USER, CategorizationSource.CONSENSUS));
        var total = 0;
        var correct = 0;
        var mismatches = new ArrayList<LiveAccuracyResponse.Mismatch>();
        for (var product : products) {
            var description = product.getNormalizedName();
            var truth = product.getCategory();
            if (description == null || description.isBlank() || truth == null) continue;
            total++;
            var extraction = productExtractor.extract(description);
            if (extraction.category() == truth) {
                correct++;
            } else if (mismatches.size() < MISMATCH_SAMPLE_CAP) {
                mismatches.add(new LiveAccuracyResponse.Mismatch(description, truth.name(),
                        extraction.category() == null ? null : extraction.category().name(),
                        extraction.categorizationSource().name()));
            }
        }
        var accuracyPct = total == 0 ? 0.0 : pct(correct, total);
        log.info("categorizer.live_accuracy total={} correct={} accuracyPct={} mismatchesSampled={}",
                total, correct, accuracyPct, mismatches.size());
        return new LiveAccuracyResponse(total, correct, accuracyPct, mismatches);
    }

    public CategorizationBenchmarkResponse run() {
        var rows = benchmarkRepository.findAll();
        var total = rows.size();
        var tally = new Tally();

        for (var row : rows) {
            var description = row.getDescription();
            var expectedCategory = row.getExpectedCategory();
            var extraction = productExtractor.extract(description);

            scoreCategory(tally, description, expectedCategory, extraction);
            scoreBrand(tally, description, row.getExpectedBrand(), extraction);
            scoreQuantity(tally, description, row.getExpectedPackSize(), row.getExpectedPackUnit(), extraction);
        }

        var response = new CategorizationBenchmarkResponse(
                total, tally.categoryCorrect, pct(tally.categoryCorrect, total), total - tally.categoryCorrect, tally.uncategorized,
                tally.brandChecked, tally.brandCorrect, pct(tally.brandCorrect, tally.brandChecked),
                tally.quantityChecked, tally.quantityCorrect, pct(tally.quantityCorrect, tally.quantityChecked),
                tally.failures);
        log.info("categorizer.benchmark categoryPct={} brandPct={} quantityPct={}",
                response.accuracyPct(), response.brandAccuracyPct(), response.quantityAccuracyPct());
        return response;
    }

    private void scoreCategory(Tally tally, String description, ProductCategory expectedCategory, ProductExtraction extraction) {
        var actualCategory = extraction.category();
        if (actualCategory == expectedCategory) {
            tally.categoryCorrect++;
            return;
        }
        if (actualCategory == null) tally.uncategorized++;
        tally.failures.add(new Failure(description, "category", expectedCategory.name(),
                actualCategory == null ? null : actualCategory.name(),
                extraction.categorizationSource().name()));
    }

    private void scoreBrand(Tally tally, String description, String expectedBrand, ProductExtraction extraction) {
        if (expectedBrand == null || expectedBrand.isEmpty()) return;
        tally.brandChecked++;
        if (sameBrand(expectedBrand, extraction.brand())) {
            tally.brandCorrect++;
        } else {
            tally.failures.add(new Failure(description, "brand", expectedBrand, extraction.brand(), "BRAND_REGISTRY"));
        }
    }

    private void scoreQuantity(Tally tally, String description, BigDecimal expectedPackSize, String expectedPackUnit,
                               ProductExtraction extraction) {
        if (expectedPackSize == null) return;
        tally.quantityChecked++;
        if (sameQuantity(expectedPackSize, expectedPackUnit, extraction.packSize(), extraction.packUnit())) {
            tally.quantityCorrect++;
        } else {
            tally.failures.add(new Failure(description, "quantity", expectedPackSize + " " + expectedPackUnit,
                    extraction.packSize() + " " + extraction.packUnit(), "PACK_REGEX"));
        }
    }

    private static final class Tally {
        private int categoryCorrect;
        private int uncategorized;
        private int brandChecked;
        private int brandCorrect;
        private int quantityChecked;
        private int quantityCorrect;
        private final List<Failure> failures = new ArrayList<>();
    }

    private static boolean sameBrand(String expected, String actual) {
        if (actual == null) return false;
        return DescriptionNormalizer.normalize(expected).equals(DescriptionNormalizer.normalize(actual));
    }

    private static boolean sameQuantity(BigDecimal expectedSize, String expectedUnit, BigDecimal actualSize, String actualUnit) {
        if (actualSize == null) return false;
        if (expectedSize.compareTo(actualSize) != 0) return false;
        return actualUnit != null && expectedUnit != null && expectedUnit.equalsIgnoreCase(actualUnit);
    }

    private static double pct(int correct, int total) {
        return total == 0 ? 0.0 : Math.round(correct * 1000.0 / total) / 10.0;
    }
}
