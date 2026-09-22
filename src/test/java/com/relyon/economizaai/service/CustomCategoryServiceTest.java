package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.CustomCategoryNotFoundException;
import com.relyon.economizaai.exception.InvalidCategoryMigrationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomCategoryServiceTest {

    @Mock private HouseholdCustomCategoryRepository customCategoryRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductCategoryOverrideService overrideService;
    @InjectMocks private CustomCategoryService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
    }

    @Test
    void list_includesAllGlobalEnumsPlusCustoms() {
        var user = user();
        var fruits = HouseholdCustomCategory.builder().id(UUID.randomUUID())
                .household(user.getHousehold()).name("Frutas").build();
        when(customCategoryRepository.findByHouseholdIdOrderByName(user.getHousehold().getId()))
                .thenReturn(List.of(fruits));

        var list = service.list(user);

        assertEquals(ProductCategory.values().length + 1, list.size());
        assertTrue(list.stream().anyMatch(c -> c.custom() && c.name().equals("Frutas")));
        assertTrue(list.stream().anyMatch(c -> !c.custom() && c.name().equals("GROCERIES")));
    }

    @Test
    void create_isIdempotentOnName() {
        var user = user();
        var existing = HouseholdCustomCategory.builder().id(UUID.randomUUID())
                .household(user.getHousehold()).name("Frutas").build();
        when(customCategoryRepository.findByHouseholdIdAndNameIgnoreCase(user.getHousehold().getId(), "Frutas"))
                .thenReturn(Optional.of(existing));

        var response = service.create(user, " Frutas ");

        assertEquals(existing.getId(), response.id());
        assertTrue(response.custom());
        verify(customCategoryRepository, never()).save(any());
    }

    @Test
    void migrate_requiresExactlyOneTarget() {
        var user = user();
        var ids1 = List.of(UUID.randomUUID());
        var targetId = UUID.randomUUID();
        assertThrows(InvalidCategoryMigrationException.class, () ->
                service.migrate(user, ids1, ProductCategory.PRODUCE, targetId));
        var ids2 = List.of(UUID.randomUUID());
        assertThrows(InvalidCategoryMigrationException.class, () ->
                service.migrate(user, ids2, null, null));
    }

    @Test
    void migrate_toCustomCategory_setsCustomOverridePerProduct() {
        var user = user();
        var custom = HouseholdCustomCategory.builder().id(UUID.randomUUID())
                .household(user.getHousehold()).name("Frutas").build();
        var pA = Product.builder().id(UUID.randomUUID()).normalizedName("maca").build();
        var pB = Product.builder().id(UUID.randomUUID()).normalizedName("uva").build();
        when(customCategoryRepository.findByIdAndHouseholdId(custom.getId(), user.getHousehold().getId()))
                .thenReturn(Optional.of(custom));
        when(productRepository.findById(pA.getId())).thenReturn(Optional.of(pA));
        when(productRepository.findById(pB.getId())).thenReturn(Optional.of(pB));

        var result = service.migrate(user, List.of(pA.getId(), pB.getId()), null, custom.getId());

        assertEquals(2, result.migrated());
        verify(overrideService, times(2)).setCustomOverride(any(), any(), eq(custom));
    }

    @Test
    void migrate_unknownCustomCategory_throws() {
        var user = user();
        var id = UUID.randomUUID();
        when(customCategoryRepository.findByIdAndHouseholdId(id, user.getHousehold().getId()))
                .thenReturn(Optional.empty());
        var productIds = List.of(UUID.randomUUID());
        assertThrows(CustomCategoryNotFoundException.class, () ->
                service.migrate(user, productIds, null, id));
    }

    @Test
    void delete_unknown_throws() {
        var user = user();
        var id = UUID.randomUUID();
        when(customCategoryRepository.findByIdAndHouseholdId(id, user.getHousehold().getId()))
                .thenReturn(Optional.empty());
        assertThrows(CustomCategoryNotFoundException.class, () -> service.delete(user, id));
    }
}
