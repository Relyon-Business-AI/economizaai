package com.relyon.economizaai.service;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.CreateCategoryRequest;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The FREE custom-category cap on {@link CustomCategoryService#create}, against the real gate. */
@ExtendWith(MockitoExtension.class)
class CustomCategoryServiceLimitTest {

    private static final CreateCategoryRequest NEW_CATEGORY = new CreateCategoryRequest("Frutas", null, null, null, null);

    @Mock private HouseholdCustomCategoryRepository customCategoryRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductCategoryOverrideService overrideService;
    @Mock private CategoryRepository categoryRepository;
    @Mock private HouseholdCacheGen householdCacheGen;

    private CustomCategoryService serviceWithEnforcement(boolean enforce) {
        var properties = new CollaborativeProperties();
        properties.getSubscription().setEnforce(enforce);
        return new CustomCategoryService(customCategoryRepository, productRepository, overrideService,
                categoryRepository, householdCacheGen, new SubscriptionGateService(properties));
    }

    private User userWithExistingCategories(SubscriptionTier tier, long existingCustomCategories) {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").subscriptionTier(tier)
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
        var householdId = user.getHousehold().getId();
        when(customCategoryRepository.findByHouseholdIdAndNameIgnoreCase(householdId, "Frutas"))
                .thenReturn(Optional.empty());
        lenient().when(customCategoryRepository.countByHouseholdId(householdId)).thenReturn(existingCustomCategories);
        lenient().when(customCategoryRepository.save(any(HouseholdCustomCategory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return user;
    }

    @Test
    void create_freeHouseholdAtTheCap_isRefusedWhenEnforcementIsOn() {
        var user = userWithExistingCategories(SubscriptionTier.FREE, 1);

        var refusal = assertThrows(PaywallException.class,
                () -> serviceWithEnforcement(true).create(user, NEW_CATEGORY));

        assertEquals("CUSTOM_CATEGORIES_UNLIMITED", refusal.getFeatureName());
        assertEquals("subscription.limit.custom_categories", refusal.getMessageKey());
        assertEquals("1", refusal.getArguments()[0]);
        verify(customCategoryRepository, never()).save(any());
        verify(customCategoryRepository, never()).delete(any());
    }

    @Test
    void create_freeHouseholdUnderTheCap_createsItsFirstCategory() {
        var user = userWithExistingCategories(SubscriptionTier.FREE, 0);

        var created = serviceWithEnforcement(true).create(user, NEW_CATEGORY);

        assertEquals("Frutas", created.name());
        verify(customCategoryRepository).save(any(HouseholdCustomCategory.class));
    }

    @Test
    void create_freeHouseholdAtTheCap_isAllowedWhenEnforcementIsOff() {
        var user = userWithExistingCategories(SubscriptionTier.FREE, 1);

        var created = serviceWithEnforcement(false).create(user, NEW_CATEGORY);

        assertEquals("Frutas", created.name());
        verify(customCategoryRepository, never()).countByHouseholdId(any());
    }

    @Test
    void create_proUserIsNeverCapped() {
        var user = userWithExistingCategories(SubscriptionTier.PRO, 50);

        var created = serviceWithEnforcement(true).create(user, NEW_CATEGORY);

        assertEquals("Frutas", created.name());
        verify(customCategoryRepository, never()).countByHouseholdId(any());
    }
}
