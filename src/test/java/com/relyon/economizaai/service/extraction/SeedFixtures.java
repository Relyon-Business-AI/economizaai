package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import org.mockito.Mockito;

import java.util.List;

/**
 * Test doubles for the DB-backed categorization seeds (formerly seed/*.csv).
 * The entry lists cover every keyword/brand the extraction tests assert on;
 * the repository mocks return them from findAll(), and the factory methods
 * hand back classifier/extractor instances already loaded from those mocks
 * (no Spring context, so @PostConstruct never fires).
 */
final class SeedFixtures {

    private SeedFixtures() {}

    static List<CuratedDictionaryEntry> curatedEntries() {
        return List.of(
                curated("arroz", "Arroz", ProductCategory.GROCERIES),
                curated("leite", "Leite", ProductCategory.MEAT_DAIRY),
                curated("veja", null, ProductCategory.CLEANING),
                curated("saco", "Saco", ProductCategory.OTHER),
                curated("saco lixo", "Saco de Lixo", ProductCategory.CLEANING),
                curated("limp", "Limpador", ProductCategory.CLEANING),
                curated("sal", "Sal", ProductCategory.GROCERIES),
                curated("file", "Filé", ProductCategory.MEAT_DAIRY),
                curated("esp", "Esponja", ProductCategory.CLEANING),
                curated("alcool", "Álcool", ProductCategory.CLEANING),
                curated("sapon", "Saponáceo", ProductCategory.CLEANING),
                curated("desinf", "Desinfetante", ProductCategory.CLEANING),
                curated("lav louca", "Lava-Louças", ProductCategory.CLEANING),
                curated("l roup", "Lava-Roupas", ProductCategory.CLEANING));
    }

    static List<BrandRegistryEntry> brandEntries() {
        return List.of(
                brand("tio joao", "Tio João"),
                brand("tio j", "Tio João"),
                brand("veja", "Veja"),
                brand("itambe", "Itambé"),
                brand("cisne", "Cisne"),
                brand("ype", "Ypê"));
    }

    static CuratedDictionaryEntryRepository curatedRepositoryMock() {
        var repository = Mockito.mock(CuratedDictionaryEntryRepository.class);
        Mockito.when(repository.findAll()).thenReturn(curatedEntries());
        return repository;
    }

    static BrandRegistryEntryRepository brandRepositoryMock() {
        var repository = Mockito.mock(BrandRegistryEntryRepository.class);
        Mockito.when(repository.findAll()).thenReturn(brandEntries());
        return repository;
    }

    static DictionaryClassifier loadedDictionaryClassifier() {
        var classifier = new DictionaryClassifier(curatedRepositoryMock());
        classifier.reloadCuratedEntries();
        return classifier;
    }

    static BrandExtractor loadedBrandExtractor() {
        var extractor = new BrandExtractor(brandRepositoryMock());
        extractor.reload();
        return extractor;
    }

    private static CuratedDictionaryEntry curated(String keyword, String genericName, ProductCategory category) {
        return CuratedDictionaryEntry.builder()
                .keyword(keyword)
                .genericName(genericName)
                .category(category)
                .build();
    }

    private static BrandRegistryEntry brand(String normalizedKey, String displayName) {
        return BrandRegistryEntry.builder()
                .normalizedKey(normalizedKey)
                .displayName(displayName)
                .build();
    }
}
