package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the LGPD account-deletion 500 (prod issue #15): rows that
 * merged into another household keep {@code origin_household_id} pointing at
 * the old household, and the V48 origin FKs (no ON DELETE action) blocked
 * deleting it. V67 switches them to ON DELETE SET NULL (mirrored in the entity
 * mapping via {@code @OnDelete}, which is what builds this test's schema) —
 * deleting an origin household must succeed and null out the reference.
 */
@DataJpaTest
@ActiveProfiles("test")
class HouseholdOriginDeletionTest {

    @Autowired private HouseholdRepository householdRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private HouseholdProductCategoryOverrideRepository overrideRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void deletingOriginHouseholdSucceedsAndNullsTheReference() {
        var originHousehold = householdRepository.save(Household.builder().inviteCode("ORIG01").build());
        var currentHousehold = householdRepository.save(Household.builder().inviteCode("CURR01").build());
        var product = productRepository.save(Product.builder()
                .ean("7891000100104").normalizedName("Arroz").category(ProductCategory.GROCERIES).build());
        // Simulates a post-merge row: lives in currentHousehold, origin points at the old one.
        var override = overrideRepository.save(HouseholdProductCategoryOverride.builder()
                .household(currentHousehold).originHousehold(originHousehold)
                .product(product).category(ProductCategory.BAKERY).build());
        entityManager.flush();
        entityManager.clear();

        householdRepository.deleteById(originHousehold.getId());
        entityManager.flush();
        entityManager.clear();

        assertTrue(householdRepository.findById(originHousehold.getId()).isEmpty(),
                "origin household must be deletable even while referenced as origin");
        var reloaded = overrideRepository.findById(override.getId()).orElseThrow();
        assertNull(reloaded.getOriginHousehold(),
                "the surviving row keeps living in its current household with origin cleared");
    }
}
