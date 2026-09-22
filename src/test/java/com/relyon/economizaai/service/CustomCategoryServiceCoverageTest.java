package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomCategoryServiceCoverageTest {

    @Mock private HouseholdCustomCategoryRepository customCategoryRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductCategoryOverrideService overrideService;
    @InjectMocks private CustomCategoryService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
    }

    @Test
    void create_savesNewWhenAbsent_andTrimsName() {
        var user = user();
        when(customCategoryRepository.findByHouseholdIdAndNameIgnoreCase(user.getHousehold().getId(), "Frutas"))
                .thenReturn(Optional.empty());
        var saved = HouseholdCustomCategory.builder().id(UUID.randomUUID())
                .household(user.getHousehold()).name("Frutas").build();
        when(customCategoryRepository.save(any(HouseholdCustomCategory.class))).thenReturn(saved);

        var response = service.create(user, "  Frutas  ");

        assertEquals(saved.getId(), response.id());
        assertEquals("Frutas", response.name());
        assertTrue(response.custom());
        verify(customCategoryRepository).save(any(HouseholdCustomCategory.class));
    }

    @Test
    void delete_existing_deletesCategory() {
        var user = user();
        var id = UUID.randomUUID();
        var category = HouseholdCustomCategory.builder().id(id)
                .household(user.getHousehold()).name("Frutas").build();
        when(customCategoryRepository.findByIdAndHouseholdId(id, user.getHousehold().getId()))
                .thenReturn(Optional.of(category));

        service.delete(user, id);

        verify(customCategoryRepository).delete(category);
    }

    @Test
    void migrate_toEnumCategory_setsOverridePerProduct() {
        var user = user();
        var productA = Product.builder().id(UUID.randomUUID()).normalizedName("maca").build();
        var productB = Product.builder().id(UUID.randomUUID()).normalizedName("uva").build();
        when(productRepository.findById(productA.getId())).thenReturn(Optional.of(productA));
        when(productRepository.findById(productB.getId())).thenReturn(Optional.of(productB));

        var result = service.migrate(user, List.of(productA.getId(), productB.getId()),
                ProductCategory.PRODUCE, null);

        assertEquals(2, result.migrated());
        assertEquals(0, result.skipped());
        verify(overrideService, times(2)).setOverride(eq(user), any(), eq(ProductCategory.PRODUCE));
        verify(customCategoryRepository, never()).findByIdAndHouseholdId(any(), any());
    }

    @Test
    void migrate_skipsUnknownProducts() {
        var user = user();
        var known = Product.builder().id(UUID.randomUUID()).normalizedName("maca").build();
        var missingId = UUID.randomUUID();
        when(productRepository.findById(known.getId())).thenReturn(Optional.of(known));
        when(productRepository.findById(missingId)).thenReturn(Optional.empty());

        var result = service.migrate(user, List.of(known.getId(), missingId),
                ProductCategory.PRODUCE, null);

        assertEquals(1, result.migrated());
        assertEquals(1, result.skipped());
        verify(overrideService, times(1)).setOverride(user, known, ProductCategory.PRODUCE);
    }

    @Test
    void productIdsIn_delegatesToOverrideService() {
        var user = user();
        var customCategoryId = UUID.randomUUID();
        var expected = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(overrideService.productIdsInCustomCategory(user.getHousehold().getId(), customCategoryId))
                .thenReturn(expected);

        assertEquals(expected, service.productIdsIn(user, customCategoryId));
    }
}
