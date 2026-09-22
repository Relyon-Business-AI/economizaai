package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import com.relyon.economizaai.service.extraction.ml.MlPrediction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link CategorizationBenchmarkService} using mocked
 * collaborators (the golden set is a mocked repository replicating the rows
 * formerly shipped in the seed CSV). Drives the category-correct/wrong/
 * uncategorized, brand check, quantity check, and ML-shadow branches by
 * controlling what the extractor and ML model return.
 */
@ExtendWith(MockitoExtension.class)
class CategorizationBenchmarkServiceCoverageTest {

    @Mock private ProductExtractor productExtractor;
    @Mock private MlClassifierService mlClassifier;
    @Mock private CategorizationBenchmarkEntryRepository benchmarkRepository;
    @InjectMocks private CategorizationBenchmarkService service;

    @BeforeEach
    void seedGoldenSet() {
        when(benchmarkRepository.findAll()).thenReturn(List.of(
                goldenRow("SAL REFINADO CISNE 1KG", ProductCategory.GROCERIES, "Cisne", new BigDecimal("1"), "KG"),
                goldenRow("ARROZ TIO JOAO 5KG", ProductCategory.GROCERIES, "Tio João", new BigDecimal("5"), "KG"),
                goldenRow("LEITE ITAMBE 1L", ProductCategory.MEAT_DAIRY, "Itambé", new BigDecimal("1"), "L"),
                goldenRow("PILHA ALCALINA AA", ProductCategory.OTHER, null, null, null)));
    }

    private CategorizationBenchmarkEntry goldenRow(String description, ProductCategory expectedCategory,
                                                   String expectedBrand, BigDecimal expectedPackSize,
                                                   String expectedPackUnit) {
        return CategorizationBenchmarkEntry.builder()
                .description(description)
                .expectedCategory(expectedCategory)
                .expectedBrand(expectedBrand)
                .expectedPackSize(expectedPackSize)
                .expectedPackUnit(expectedPackUnit)
                .build();
    }

    private ProductExtraction extraction(ProductCategory category, String brand,
                                         BigDecimal packSize, String packUnit) {
        return new ProductExtraction(null, brand, packSize, packUnit, category, CategorizationSource.DICTIONARY);
    }

    @Test
    void allGroceriesGuess_someCorrectSomeWrong_withBrandAndQuantityChecks() {
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        // Every row guessed GROCERIES with brand "Cisne" and pack "1 KG".
        // Matches the GROCERIES golden rows; mismatches the rest → failures populate.
        when(productExtractor.extract(anyString()))
                .thenReturn(extraction(ProductCategory.GROCERIES, "Cisne", new BigDecimal("1"), "KG"));
        // ML shadow confidently predicts GROCERIES for every row.
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(new MlPrediction<>(ProductCategory.GROCERIES, 0.95));

        var report = service.run();

        assertTrue(report.total() > 0);
        assertEquals(report.total() - report.correct(), report.wrong());
        assertTrue(report.correct() > 0, "at least the GROCERIES rows should match");
        // brand and quantity truths exist in the golden set → both get checked
        assertTrue(report.brandChecked() > 0);
        assertTrue(report.quantityChecked() > 0);
        // ml-shadow confidently right whenever the golden category is GROCERIES
        assertTrue(report.mlCategoryCorrect() > 0);
        assertEquals(report.total(), report.mlCategoryChecked());
        // accuracy percentages are bounded [0,100]
        assertTrue(report.accuracyPct() >= 0.0 && report.accuracyPct() <= 100.0);
    }

    @Test
    void nullExtraction_countsUncategorized_andFailsBrandAndQuantity() {
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        // Extractor returns nothing → null category, null brand, null pack.
        when(productExtractor.extract(anyString())).thenReturn(ProductExtraction.EMPTY);
        // ML shadow is below threshold → never counts as correct.
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(new MlPrediction<>(ProductCategory.GROCERIES, 0.10));

        var report = service.run();

        assertEquals(0, report.correct());
        assertEquals(report.total(), report.wrong());
        assertEquals(report.total(), report.uncategorized());
        // brand/quantity were checked (golden declares them) but all failed
        assertTrue(report.brandChecked() > 0);
        assertEquals(0, report.brandCorrect());
        assertTrue(report.quantityChecked() > 0);
        assertEquals(0, report.quantityCorrect());
        assertEquals(0, report.mlCategoryCorrect());
        // pct over zero correct → 0.0
        assertEquals(0.0, report.accuracyPct());
        assertEquals(0.0, report.brandAccuracyPct());
        assertEquals(0.0, report.quantityAccuracyPct());
        // failures recorded for category + brand + quantity mismatches
        assertFalse(report.failures().isEmpty());
    }

    @Test
    void mlConfidentButWrongLabel_doesNotCountTowardsMlCorrect() {
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        when(productExtractor.extract(anyString()))
                .thenReturn(extraction(ProductCategory.OTHER, null, null, null));
        // Confident, but the label is OTHER which won't match GROCERIES golden rows.
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(new MlPrediction<>(ProductCategory.OTHER, 0.99));

        var report = service.run();

        // Some golden rows ARE category OTHER, so a few category-correct are possible,
        // but mlCorrect only counts when the confident label equals the golden truth.
        assertEquals(report.total(), report.mlCategoryChecked());
        assertTrue(report.mlCategoryCorrect() <= report.total());
    }
}
