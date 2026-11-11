package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyTierResolverTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryResolver categoryResolver;

    private CollaborativeProperties properties;
    private PrivacyTierResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        resolver = new PrivacyTierResolver(productRepository, categoryResolver, properties);
    }

    private UUID productInGroup(String groupSlug) {
        var productId = UUID.randomUUID();
        var leafId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().id(productId).categoryId(leafId).build()));
        when(categoryResolver.groupIdOf(leafId)).thenReturn(groupId);
        when(categoryResolver.slugForId(groupId)).thenReturn(groupSlug);
        return productId;
    }

    @Test
    void healthGroupProductIsSensitive() {
        assertTrue(resolver.isSensitiveProduct(productInGroup("saude")));
    }

    @Test
    void groceryGroupProductIsNotSensitive() {
        assertFalse(resolver.isSensitiveProduct(productInGroup("hortifruti")));
    }

    @Test
    void sensitiveGroupsAreConfigurable() {
        properties.getCollaborative().setSensitiveCategoryGroups(List.of("saude", "higiene"));

        assertTrue(resolver.isSensitiveProduct(productInGroup("higiene")));
    }

    @Test
    void productWithoutNodeFallsBackToTheLegacyHealthEnum() {
        var healthId = UUID.randomUUID();
        var groceryId = UUID.randomUUID();
        when(productRepository.findById(healthId))
                .thenReturn(Optional.of(Product.builder().id(healthId).category(ProductCategory.HEALTH).build()));
        when(productRepository.findById(groceryId))
                .thenReturn(Optional.of(Product.builder().id(groceryId).category(ProductCategory.GROCERIES).build()));

        assertTrue(resolver.isSensitiveProduct(healthId));
        assertFalse(resolver.isSensitiveProduct(groceryId));
    }

    @Test
    void unknownProductIsTreatedAsSensitive() {
        var missing = UUID.randomUUID();
        when(productRepository.findById(missing)).thenReturn(Optional.empty());

        assertTrue(resolver.isSensitiveProduct(missing));
    }

    @Test
    void onlyClassifiedGroceryStoresGetTheRelaxedTier() {
        var pharmacy = MarketLocation.builder().cnpj("1").segment(MerchantSegment.PHARMACY).build();
        var supermarket = MarketLocation.builder().cnpj("2").segment(MerchantSegment.SUPERMARKET).build();
        var foodRetail = MarketLocation.builder().cnpj("3").segment(MerchantSegment.FOOD_RETAIL).build();

        assertTrue(PrivacyTierResolver.isSensitiveMarket(pharmacy));
        assertFalse(PrivacyTierResolver.isSensitiveMarket(supermarket));
        assertFalse(PrivacyTierResolver.isSensitiveMarket(foodRetail));
        assertTrue(PrivacyTierResolver.anySensitiveMarket(List.of(supermarket, pharmacy)));
        assertFalse(PrivacyTierResolver.anySensitiveMarket(List.of(supermarket, foodRetail)));
    }

    @Test
    void unknownOrUnclassifiedStoreSegmentIsSensitive() {
        var unclassified = MarketLocation.builder().cnpj("4").segment(MerchantSegment.UNKNOWN).build();
        var other = MarketLocation.builder().cnpj("5").segment(MerchantSegment.OTHER).build();
        var withoutSegment = MarketLocation.builder().cnpj("6").build();
        withoutSegment.setSegment(null);
        var supermarket = MarketLocation.builder().cnpj("2").segment(MerchantSegment.SUPERMARKET).build();
        var storesWithOneUnregistered = new ArrayList<MarketLocation>();
        storesWithOneUnregistered.add(supermarket);
        storesWithOneUnregistered.add(null);

        assertTrue(PrivacyTierResolver.isSensitiveMarket(null), "a store with no location row is unclassified");
        assertTrue(PrivacyTierResolver.isSensitiveMarket(unclassified));
        assertTrue(PrivacyTierResolver.isSensitiveMarket(other));
        assertTrue(PrivacyTierResolver.isSensitiveMarket(withoutSegment));
        assertTrue(PrivacyTierResolver.anySensitiveMarket(storesWithOneUnregistered));
    }

    @Test
    void productWithoutAnyCategoryStaysGrocery() {
        var uncategorizedId = UUID.randomUUID();
        when(productRepository.findById(uncategorizedId))
                .thenReturn(Optional.of(Product.builder().id(uncategorizedId).build()));

        assertFalse(resolver.isSensitiveProduct(uncategorizedId));
    }

    @Test
    void thresholdsFollowTheTier() {
        properties.getCollaborative().setMinHouseholdsForPublic(3);
        properties.getCollaborative().setMinObservationsPerProductMarket(5);
        properties.getCollaborative().setMinHouseholdsForGrocery(1);
        properties.getCollaborative().setMinObservationsForGrocery(1);

        assertEquals(new PrivacyTierResolver.Thresholds(3, 5), resolver.thresholdsFor(true));
        assertEquals(new PrivacyTierResolver.Thresholds(1, 1), resolver.thresholdsFor(false));
        assertTrue(new PrivacyTierResolver.Thresholds(3, 5).blocks(5, 2));
        assertTrue(new PrivacyTierResolver.Thresholds(3, 5).blocks(4, 3));
        assertFalse(new PrivacyTierResolver.Thresholds(1, 1).blocks(1, 1));
    }
}
