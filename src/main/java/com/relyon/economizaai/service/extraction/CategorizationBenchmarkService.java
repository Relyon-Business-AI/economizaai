package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse.Failure;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
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
 * Runs the same cascade as ingestion and reports per-field accuracy, plus a
 * SHADOW measurement of the ML model alone (so we keep validating it even
 * while it's gated out of the live cascade).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationBenchmarkService {

    private final ProductExtractor productExtractor;
    private final MlClassifierService mlClassifier;
    private final CategorizationBenchmarkEntryRepository benchmarkRepository;

    public CategorizationBenchmarkResponse run() {
        var rows = benchmarkRepository.findAll();
        var total = rows.size();
        var tally = new Tally();
        var threshold = mlClassifier.getConfidenceThreshold();

        for (var row : rows) {
            var description = row.getDescription();
            var expectedCategory = row.getExpectedCategory();
            var extraction = productExtractor.extract(description);

            scoreCategory(tally, description, expectedCategory, extraction);
            scoreBrand(tally, description, row.getExpectedBrand(), extraction);
            scoreQuantity(tally, description, row.getExpectedPackSize(), row.getExpectedPackUnit(), extraction);
            scoreMlShadow(tally, description, expectedCategory, threshold);
        }

        var response = new CategorizationBenchmarkResponse(
                total, tally.categoryCorrect, pct(tally.categoryCorrect, total), total - tally.categoryCorrect, tally.uncategorized,
                tally.brandChecked, tally.brandCorrect, pct(tally.brandCorrect, tally.brandChecked),
                tally.quantityChecked, tally.quantityCorrect, pct(tally.quantityCorrect, tally.quantityChecked),
                total, tally.mlCorrect, pct(tally.mlCorrect, total),
                tally.failures);
        log.info("categorizer.benchmark categoryPct={} brandPct={} quantityPct={} mlShadowPct={}",
                response.accuracyPct(), response.brandAccuracyPct(), response.quantityAccuracyPct(),
                response.mlCategoryAccuracyPct());
        return response;
    }

    /** Category via the applied cascade. */
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

    /** Brand — only when the golden row declares one. */
    private void scoreBrand(Tally tally, String description, String expectedBrand, ProductExtraction extraction) {
        if (expectedBrand == null || expectedBrand.isEmpty()) return;
        tally.brandChecked++;
        if (sameBrand(expectedBrand, extraction.brand())) {
            tally.brandCorrect++;
        } else {
            tally.failures.add(new Failure(description, "brand", expectedBrand, extraction.brand(), "BRAND_REGISTRY"));
        }
    }

    /** Quantity (pack size + unit) — only when the golden row declares one. */
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

    /** ML shadow — the model alone, regardless of whether it's applied live. */
    private void scoreMlShadow(Tally tally, String description, ProductCategory expectedCategory, double threshold) {
        var mlPrediction = mlClassifier.predictCategory(description);
        if (mlPrediction.isConfident(threshold) && mlPrediction.label() == expectedCategory) {
            tally.mlCorrect++;
        }
    }

    private static final class Tally {
        private int categoryCorrect;
        private int uncategorized;
        private int brandChecked;
        private int brandCorrect;
        private int quantityChecked;
        private int quantityCorrect;
        private int mlCorrect;
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
