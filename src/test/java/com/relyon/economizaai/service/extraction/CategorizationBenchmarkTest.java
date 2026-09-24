package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard + visibility for categorization quality. In the test context
 * the ML model isn't trained, so this measures the DICTIONARY alone — exactly
 * what dictionary edits move. The golden set now lives in the
 * categorization_benchmark_entries table (grown via the admin import endpoint
 * in production); here it is seeded alongside representative curated-dictionary
 * and brand-registry rows so the floor stays meaningful. The live
 * POST /categorizer/benchmark (ADMIN) reports the dictionary + trained-ML number.
 */
@SpringBootTest
@ActiveProfiles("test")
class CategorizationBenchmarkTest {

    private static final double MIN_DICTIONARY_ACCURACY_PCT = 90.0;

    @Autowired private CategorizationBenchmarkService benchmarkService;
    @Autowired private CategorizationBenchmarkEntryRepository benchmarkRepository;
    @Autowired private CuratedDictionaryEntryRepository curatedRepository;
    @Autowired private BrandRegistryEntryRepository brandRepository;
    @Autowired private DictionaryClassifier dictionaryClassifier;
    @Autowired private BrandExtractor brandExtractor;

    @BeforeEach
    void seedGoldenSet() {
        benchmarkRepository.deleteAll();
        curatedRepository.deleteAll();
        brandRepository.deleteAll();
        curatedRepository.saveAll(SeedFixtures.curatedEntries());
        brandRepository.saveAll(SeedFixtures.brandEntries());
        benchmarkRepository.saveAll(List.of(
                goldenRow("ARROZ TIO J TP1 5KG", ProductCategory.GROCERIES, "Tio João", new BigDecimal("5"), "KG"),
                goldenRow("LEITE INTEGRAL ITAMBE 1L", ProductCategory.MEAT_DAIRY, "Itambé", new BigDecimal("1"), "L"),
                goldenRow("LIMP COZ VEJA LIMAO SQ500ML PROM", ProductCategory.CLEANING, "Veja", new BigDecimal("500"), "ML"),
                goldenRow("SAL REFINADO EXTRA IOD CISNE 1KG", ProductCategory.GROCERIES, "Cisne", null, null),
                goldenRow("SACO LIXO 50L C/20", ProductCategory.CLEANING, null, null, null),
                goldenRow("DESINF PINHO SOL NAT LAVANDA 1L", ProductCategory.CLEANING, null, null, null),
                goldenRow("ALCOOL LIQ ZEPPELIN ECOBAC 46 1L", ProductCategory.CLEANING, null, null, null),
                goldenRow("LAV LOUCA YPE COCO 500ML", ProductCategory.CLEANING, "Ypê", null, null),
                goldenRow("ESP SCOTCH-BRITE MULTIUSO L4P3", ProductCategory.CLEANING, null, null, null),
                goldenRow("FILE CX/SC FGO NAT VD IQF 1KG", ProductCategory.MEAT_DAIRY, null, null, null)));
        dictionaryClassifier.reloadCuratedEntries();
        brandExtractor.reload();
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

    @Test
    void dictionaryAccuracy_meetsFloor() {
        var report = benchmarkService.run();
        System.out.printf("[benchmark] dictionary-only accuracy = %.1f%% (%d/%d). Failures:%n",
                report.accuracyPct(), report.correct(), report.total());
        report.failures().forEach(failure ->
                System.out.printf("  %-42s expected=%s got=%s [%s]%n",
                        failure.description(), failure.expected(), failure.got(), failure.source()));
        assertTrue(report.accuracyPct() >= MIN_DICTIONARY_ACCURACY_PCT,
                "dictionary accuracy " + report.accuracyPct() + "% fell below floor " + MIN_DICTIONARY_ACCURACY_PCT + "%");
    }
}
