package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdProductCategoryOverrideServiceTest {

    @Mock private HouseholdProductCategoryOverrideRepository overrideRepository;
    @InjectMocks private HouseholdProductCategoryOverrideService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("milho").build();
    }

    @Test
    void setOverride_createsWhenAbsent() {
        var user = user();
        var product = product();
        when(overrideRepository.findByHouseholdIdAndProductId(user.getHousehold().getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.setOverride(user, product, ProductCategory.GROCERIES);

        var captor = ArgumentCaptor.forClass(HouseholdProductCategoryOverride.class);
        verify(overrideRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(ProductCategory.GROCERIES, saved.getCategory());
        assertEquals(product.getId(), saved.getProduct().getId());
        assertEquals(user.getHousehold().getId(), saved.getHousehold().getId());
    }

    @Test
    void setOverride_updatesExisting() {
        var user = user();
        var product = product();
        var existing = HouseholdProductCategoryOverride.builder()
                .household(user.getHousehold()).product(product).category(ProductCategory.OTHER).build();
        when(overrideRepository.findByHouseholdIdAndProductId(user.getHousehold().getId(), product.getId()))
                .thenReturn(Optional.of(existing));

        service.setOverride(user, product, ProductCategory.MEAT_DAIRY);

        verify(overrideRepository).save(existing);
        assertEquals(ProductCategory.MEAT_DAIRY, existing.getCategory());
    }

    @Test
    void overridesByProduct_mapsProductIdToCategory() {
        var household = UUID.randomUUID();
        var product = product();
        var override = HouseholdProductCategoryOverride.builder()
                .household(Household.builder().id(household).build())
                .product(product).category(ProductCategory.CLEANING).build();
        when(overrideRepository.findByHouseholdIdAndProductIdIn(eq(household), any()))
                .thenReturn(List.of(override));

        var map = service.overridesByProduct(household, List.of(product.getId()));

        assertEquals("CLEANING", map.get(product.getId()));
    }

    @Test
    void overridesByProduct_emptyProductIds_shortCircuits() {
        assertEquals(0, service.overridesByProduct(UUID.randomUUID(), List.of()).size());
    }
}
