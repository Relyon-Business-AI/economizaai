package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.ProductCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenFoodFactsCategoryMapperTest {

    @Test
    void coffee_mapsToBeveragesNotProduce() {
        // Coffee carries en:coffees AND the broad en:plant-based-foods — the old
        // mapper let the broad tag win and bucketed it as PRODUCE.
        var tags = "en:plant-based-foods-and-beverages,en:plant-based-foods,en:coffees,en:ground-coffees";
        assertEquals(ProductCategory.BEVERAGES, OpenFoodFactsCategoryMapper.map(tags));
    }

    @Test
    void sodas_mapToBeverages() {
        assertEquals(ProductCategory.BEVERAGES,
                OpenFoodFactsCategoryMapper.map("en:beverages,en:sodas,pt:refrigerantes"));
    }

    @Test
    void condensedMilk_mapsToMeatDairy() {
        assertEquals(ProductCategory.MEAT_DAIRY,
                OpenFoodFactsCategoryMapper.map("en:dairies,en:sweetened-condensed-milks"));
    }

    @Test
    void freshVegetable_mapsToProduce() {
        assertEquals(ProductCategory.PRODUCE,
                OpenFoodFactsCategoryMapper.map("en:fresh-foods,en:vegetables,en:fresh-vegetables"));
    }

    @Test
    void pasta_mapsToGroceriesNotProduce() {
        // Pasta also carries the broad plant-based tag; must land in GROCERIES.
        assertEquals(ProductCategory.GROCERIES,
                OpenFoodFactsCategoryMapper.map("en:plant-based-foods,en:cereals-and-potatoes,en:pastas"));
    }

    @Test
    void chocolate_mapsToGroceries() {
        assertEquals(ProductCategory.GROCERIES,
                OpenFoodFactsCategoryMapper.map("en:snacks,en:sweet-snacks,en:chocolates"));
    }

    @Test
    void cleaningProduct_mapsToCleaning() {
        assertEquals(ProductCategory.CLEANING,
                OpenFoodFactsCategoryMapper.map("en:household-supplies,en:laundry"));
    }

    @Test
    void unknownOrEmptyTags_mapToOther() {
        assertEquals(ProductCategory.OTHER, OpenFoodFactsCategoryMapper.map(""));
        assertEquals(ProductCategory.OTHER, OpenFoodFactsCategoryMapper.map(null));
        assertEquals(ProductCategory.OTHER, OpenFoodFactsCategoryMapper.map("en:some-unmapped-tag"));
    }

    @Test
    void tagBoundary_doesNotFireOnSubstringNeighbour() {
        // "en:tea" must not match inside "en:steaks"; that row is a meat.
        assertEquals(ProductCategory.MEAT_DAIRY,
                OpenFoodFactsCategoryMapper.map("en:meats,en:steaks"));
    }

    // ── Portuguese-only tags (many Brazilian OFF entries carry no en: tag) ──────

    @Test
    void portugueseOnlyCereais_mapsToGroceries() {
        // The Neston case: OFF tags it only "pt:cereais" — the en:-only mapper
        // dropped it to OTHER/null.
        assertEquals(ProductCategory.GROCERIES, OpenFoodFactsCategoryMapper.map("pt:cereais"));
    }

    @Test
    void portugueseAccentedTag_isFolded() {
        // "pt:laticínios" (accented) must hit the accent-free "pt:laticinios" tag.
        assertEquals(ProductCategory.MEAT_DAIRY, OpenFoodFactsCategoryMapper.map("pt:laticínios"));
    }

    @Test
    void portugueseHygieneAndCleaning_mapCorrectly() {
        assertEquals(ProductCategory.PERSONAL_CARE, OpenFoodFactsCategoryMapper.map("pt:higiene-pessoal"));
        assertEquals(ProductCategory.CLEANING, OpenFoodFactsCategoryMapper.map("pt:produtos-de-limpeza"));
    }

    @Test
    void portugueseBeverage_winsOverProduce() {
        // pt:sucos (juice) must land in BEVERAGES, not PRODUCE via a fruit tag.
        assertEquals(ProductCategory.BEVERAGES, OpenFoodFactsCategoryMapper.map("pt:frutas,pt:sucos"));
    }

    @Test
    void beautyAndCleaningTags_mapCorrectly() {
        assertEquals(ProductCategory.PERSONAL_CARE, OpenFoodFactsCategoryMapper.map("en:perfumes"));
        assertEquals(ProductCategory.PERSONAL_CARE, OpenFoodFactsCategoryMapper.map("pt:protecao-solar"));
        assertEquals(ProductCategory.CLEANING, OpenFoodFactsCategoryMapper.map("en:detergents"));
        assertEquals(ProductCategory.CLEANING, OpenFoodFactsCategoryMapper.map("pt:inseticidas"));
    }

    @Test
    void petFood_mapsToPetSupplies_notFoodCategory() {
        // Pet food carries meat/cereal tags; PET_SUPPLIES must win over MEAT_DAIRY.
        assertEquals(ProductCategory.PET_SUPPLIES,
                OpenFoodFactsCategoryMapper.map("en:meats,en:dog-food,en:pet-food"));
        assertEquals(ProductCategory.PET_SUPPLIES, OpenFoodFactsCategoryMapper.map("pt:racao"));
    }
}
