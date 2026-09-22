package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdProductCategoryOverrideServiceCoverageTest {

    @Mock private HouseholdProductCategoryOverrideRepository overrideRepository;
    @InjectMocks private HouseholdProductCategoryOverrideService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("milho").build();
    }

    private HouseholdCustomCategory customCategory(Household household) {
        return HouseholdCustomCategory.builder().id(UUID.randomUUID())
                .household(household).name("Frutas").build();
    }

    @Test
    void setCustomOverride_createsWhenAbsent() {
        var user = user();
        var product = product();
        var custom = customCategory(user.getHousehold());
        when(overrideRepository.findByHouseholdIdAndProductId(user.getHousehold().getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.setCustomOverride(user, product, custom);

        var captor = ArgumentCaptor.forClass(HouseholdProductCategoryOverride.class);
        verify(overrideRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(custom, saved.getCustomCategory());
        assertNull(saved.getCategory());
        assertEquals(product.getId(), saved.getProduct().getId());
        assertEquals(user.getHousehold().getId(), saved.getHousehold().getId());
    }

    @Test
    void setCustomOverride_updatesExistingClearingEnumCategory() {
        var user = user();
        var product = product();
        var custom = customCategory(user.getHousehold());
        var existing = HouseholdProductCategoryOverride.builder()
                .household(user.getHousehold()).product(product)
                .category(ProductCategory.OTHER).build();
        when(overrideRepository.findByHouseholdIdAndProductId(user.getHousehold().getId(), product.getId()))
                .thenReturn(Optional.of(existing));

        service.setCustomOverride(user, product, custom);

        verify(overrideRepository).save(existing);
        assertEquals(custom, existing.getCustomCategory());
        assertNull(existing.getCategory());
    }

    @Test
    void productIdsInCustomCategory_mapsToProductIds() {
        var householdId = UUID.randomUUID();
        var customCategoryId = UUID.randomUUID();
        var productA = product();
        var productB = product();
        var overrideA = HouseholdProductCategoryOverride.builder().product(productA).build();
        var overrideB = HouseholdProductCategoryOverride.builder().product(productB).build();
        when(overrideRepository.findByHouseholdIdAndCustomCategoryId(householdId, customCategoryId))
                .thenReturn(List.of(overrideA, overrideB));

        var result = service.productIdsInCustomCategory(householdId, customCategoryId);

        assertEquals(2, result.size());
        assertTrue(result.contains(productA.getId()));
        assertTrue(result.contains(productB.getId()));
    }

    @Test
    void productIdsInCustomCategory_empty_returnsEmptyList() {
        var householdId = UUID.randomUUID();
        var customCategoryId = UUID.randomUUID();
        when(overrideRepository.findByHouseholdIdAndCustomCategoryId(householdId, customCategoryId))
                .thenReturn(List.of());

        assertTrue(service.productIdsInCustomCategory(householdId, customCategoryId).isEmpty());
    }

    @Test
    void overridesByProduct_filtersOutNullEffectiveLabel() {
        var householdId = UUID.randomUUID();
        var labeledProduct = product();
        var unlabeledProduct = product();
        var labeled = HouseholdProductCategoryOverride.builder()
                .product(labeledProduct)
                .category(ProductCategory.CLEANING).build();
        var unlabeled = HouseholdProductCategoryOverride.builder()
                .product(unlabeledProduct).build(); // no category and no custom -> effectiveLabel null
        when(overrideRepository.findByHouseholdIdAndProductIdIn(eq(householdId), any()))
                .thenReturn(List.of(labeled, unlabeled));

        var map = service.overridesByProduct(householdId,
                List.of(labeledProduct.getId(), unlabeledProduct.getId()));

        assertEquals(1, map.size());
        assertEquals("CLEANING", map.get(labeledProduct.getId()));
        assertNull(map.get(unlabeledProduct.getId()));
    }

    @Test
    void overridesByProduct_customCategoryLabelUsed() {
        var householdId = UUID.randomUUID();
        var product = product();
        var custom = HouseholdCustomCategory.builder().id(UUID.randomUUID()).name("Frutas").build();
        var override = HouseholdProductCategoryOverride.builder()
                .product(product).customCategory(custom).build();
        when(overrideRepository.findByHouseholdIdAndProductIdIn(eq(householdId), any()))
                .thenReturn(List.of(override));

        var map = service.overridesByProduct(householdId, List.of(product.getId()));

        assertEquals("Frutas", map.get(product.getId()));
    }
}
