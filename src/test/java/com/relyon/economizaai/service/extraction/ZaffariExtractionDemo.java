package com.relyon.economizaai.service.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZaffariExtractionDemo {

    private static final List<String> ITEMS = List.of(
            "FILE CX/SC FGO NAT VD IQF 1KG",
            "LIMP COZ VEJA LIMAO SQ500ML PROM",
            "SAL REFINADO EXTRA IOD CISNE 1KG",
            "ESP SCOTCH-BRITE MULTIUSO L4P3",
            "ALCOOL LIQ ZEPPELIN ECOBAC 46 1L",
            "LIMP VEJA PERF ENVOLVENTE 2L",
            "LIMP VDR VEJA VIDREX CR SH400ML",
            "SAPON CREM CIF LIM 450ML",
            "COALA CHA BRANCO AER400ML",
            "DESINF PINHO SOL NAT LAVANDA 1L",
            "LAV LOUCA YPE COCO 500ML",
            "LIMP COALA AMEIXA DOURADA 120ML",
            "SACO LIXO DR RECICL 50L C/20",
            "SACO LIXO UTILO 50L C/30",
            "L ROUP LQ GIRANDOSOL HIP 2L"
    );

    private ProductExtractor extractor;

    @BeforeEach
    void setUp() {
        var brandExtractor = SeedFixtures.loadedBrandExtractor();
        var dictionaryClassifier = SeedFixtures.loadedDictionaryClassifier();
        extractor = new ProductExtractor(brandExtractor, dictionaryClassifier);
    }

    @Test
    void demonstrateExtractionOnRealZaffariReceipt() {
        System.out.println("\n=== Extraction results for real Zaffari receipt ===\n");
        System.out.printf("%-40s %-15s %-15s %-10s %-15s%n",
                "raw description", "genericName", "brand", "size", "category");
        System.out.println("-".repeat(100));
        var matched = 0;
        for (var raw : ITEMS) {
            var e = extractor.extract(raw);
            var size = e.packSize() != null ? e.packSize() + " " + e.packUnit() : "-";
            System.out.printf("%-40s %-15s %-15s %-10s %-15s%n",
                    raw,
                    e.genericName() != null ? e.genericName() : "-",
                    e.brand() != null ? e.brand() : "-",
                    size,
                    e.category() != null ? e.category() : "-");
            if (e.category() != null) matched++;
        }
        System.out.printf("%n=== Coverage: %d/%d items got a category (%.0f%%) ===%n",
                matched, ITEMS.size(), 100.0 * matched / ITEMS.size());

        // Beyond the printed demo, assert the extractor actually categorizes most
        // of this real Zaffari receipt - otherwise a regression in extraction would
        // pass silently. The dictionary alone (ML mocked off) should cover the
        // majority of these common grocery items.
        assertThat(matched)
                .as("dictionary-only category coverage on a real Zaffari receipt")
                .isGreaterThanOrEqualTo(ITEMS.size() / 2);
    }
}
