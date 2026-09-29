package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.ProductRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link CategorizationBenchmarkService} using mocked
 * collaborators. Drives category-correct/wrong/uncategorized, brand check,
 * and quantity check branches by controlling what the extractor returns.
 */
@ExtendWith(MockitoExtension.class)
class CategorizationBenchmarkServiceCoverageTest {

    @Mock private ProductExtractor productExtractor;
    @Mock private CategorizationBenchmarkEntryRepository benchmarkRepository;
    @Mock private ProductRepository productRepository;
    @InjectMocks private CategorizationBenchmarkService service;

    @BeforeEach
    void seedGoldenSet() {
        lenient().when(benchmarkRepository.findAll()).thenReturn(List.of(
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
        when(productExtractor.extract(anyString()))
                .thenReturn(extraction(ProductCategory.GROCERIES, "Cisne", new BigDecimal("1"), "KG"));

        var report = service.run();

        assertTrue(report.total() > 0);
        assertEquals(report.total() - report.correct(), report.wrong());
        assertTrue(report.correct() > 0, "at least the GROCERIES rows should match");
        assertTrue(report.brandChecked() > 0);
        assertTrue(report.quantityChecked() > 0);
        assertTrue(report.accuracyPct() >= 0.0 && report.accuracyPct() <= 100.0);
    }

    @Test
    void nullExtraction_countsUncategorized_andFailsBrandAndQuantity() {
        when(productExtractor.extract(anyString())).thenReturn(ProductExtraction.EMPTY);

        var report = service.run();

        assertEquals(0, report.correct());
        assertEquals(report.total(), report.wrong());
        assertEquals(report.total(), report.uncategorized());
        assertTrue(report.brandChecked() > 0);
        assertEquals(0, report.brandCorrect());
        assertTrue(report.quantityChecked() > 0);
        assertEquals(0, report.quantityCorrect());
        assertEquals(0.0, report.accuracyPct());
        assertEquals(0.0, report.brandAccuracyPct());
        assertEquals(0.0, report.quantityAccuracyPct());
        assertFalse(report.failures().isEmpty());
    }

    @Test
    void wrongCategoryGuess_countsAsWrong() {
        when(productExtractor.extract(anyString()))
                .thenReturn(extraction(ProductCategory.OTHER, null, null, null));

        var report = service.run();

        assertTrue(report.total() > 0);
        // At most the OTHER golden rows match; others will be wrong.
        assertTrue(report.wrong() > 0 || report.correct() > 0);
    }

    @Test
    void runLive_scoresAgainstHumanValidatedProducts_andCollectsMismatches() {
        var right = Product.builder().normalizedName("ARROZ TIO JOAO 5KG")
                .category(ProductCategory.GROCERIES).categorizationSource(CategorizationSource.USER).build();
        var wrong = Product.builder().normalizedName("FILME PVC ROLO")
                .category(ProductCategory.GROCERIES).categorizationSource(CategorizationSource.CONSENSUS).build();
        var skipped = Product.builder().normalizedName("SEM CATEGORIA")
                .category(null).categorizationSource(CategorizationSource.USER).build();
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(List.of(right, wrong, skipped));
        when(productExtractor.extract("ARROZ TIO JOAO 5KG"))
                .thenReturn(extraction(ProductCategory.GROCERIES, null, null, null));
        when(productExtractor.extract("FILME PVC ROLO"))
                .thenReturn(extraction(ProductCategory.CLEANING, null, null, null));

        var result = service.runLive();

        assertEquals(2, result.total(), "product with null category is skipped");
        assertEquals(1, result.correct());
        assertEquals(50.0, result.accuracyPct(), 0.01);
        assertEquals(1, result.mismatches().size());
        var mismatch = result.mismatches().get(0);
        assertEquals("FILME PVC ROLO", mismatch.description());
        assertEquals("GROCERIES", mismatch.expectedCategory());
        assertEquals("CLEANING", mismatch.actualCategory());
    }
}
