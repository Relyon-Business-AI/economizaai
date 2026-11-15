package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The household-wide reads of settings that are stored under a single member. */
@DataJpaTest
@ActiveProfiles("test")
class HouseholdSharedSettingsRepositoryTest {

    @Autowired private UserWatchedMarketRepository watchedMarketRepository;
    @Autowired private NotificationRuleRepository ruleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private ProductRepository productRepository;

    private Household household;
    private User firstMember;
    private User secondMember;
    private User outsider;

    @BeforeEach
    void setUp() {
        household = householdRepository.save(Household.builder().inviteCode("SHARED1").build());
        var otherHousehold = householdRepository.save(Household.builder().inviteCode("OTHER01").build());
        firstMember = userRepository.save(userIn(household, "shared41823@economizaai.app"));
        secondMember = userRepository.save(userIn(household, "shared41824@economizaai.app"));
        outsider = userRepository.save(userIn(otherHousehold, "shared41825@economizaai.app"));
    }

    private User userIn(Household memberHousehold, String email) {
        return User.builder()
                .name("Member").email(email).password("x")
                .household(memberHousehold)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build();
    }

    private void pin(User owner, String marketCnpj) {
        watchedMarketRepository.save(UserWatchedMarket.builder().user(owner).marketCnpj(marketCnpj).build());
    }

    private NotificationRule priceAlert(User owner, Product product) {
        return ruleRepository.save(NotificationRule.builder()
                .user(owner).type(NotificationType.PRICE_DROP).product(product)
                .thresholdPrice(new BigDecimal("5.00")).build());
    }

    private Set<String> householdPinnedCnpjs() {
        return watchedMarketRepository.findAllByUserHouseholdId(household.getId()).stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .collect(Collectors.toSet());
    }

    @Test
    void watchedMarketsOfAHouseholdAreThePinsOfAllItsMembersOnly() {
        pin(firstMember, "11111111000111");
        pin(secondMember, "22222222000122");
        pin(outsider, "33333333000133");

        assertEquals(Set.of("11111111000111", "22222222000122"), householdPinnedCnpjs());
    }

    @Test
    void removingAMarketFromTheHouseholdRemovesThePinOfEveryMemberAndOfNobodyElse() {
        pin(firstMember, "11111111000111");
        pin(secondMember, "11111111000111");
        pin(secondMember, "22222222000122");
        pin(outsider, "11111111000111");

        watchedMarketRepository.deleteByUserHouseholdIdAndMarketCnpj(household.getId(), "11111111000111");
        watchedMarketRepository.flush();

        assertEquals(Set.of("22222222000122"), householdPinnedCnpjs());
        assertEquals(1, watchedMarketRepository.findAllByUserId(outsider.getId()).size());
    }

    @Test
    void priceAlertsOfAHouseholdAreTheOnesOfAllItsMembersOnly() {
        var milk = productRepository.save(Product.builder()
                .ean("7890000000017").normalizedName("Leite").category(ProductCategory.GROCERIES).build());
        var rice = productRepository.save(Product.builder()
                .ean("7890000000024").normalizedName("Arroz").category(ProductCategory.GROCERIES).build());
        var firstMembersAlert = priceAlert(firstMember, milk);
        var secondMembersAlert = priceAlert(secondMember, rice);
        priceAlert(outsider, milk);
        ruleRepository.save(NotificationRule.builder()
                .user(firstMember).type(NotificationType.BUDGET).thresholdPrice(new BigDecimal("900.00")).build());

        var householdAlertIds = ruleRepository
                .findAllByHouseholdIdAndTypeFetchProduct(household.getId(), NotificationType.PRICE_DROP).stream()
                .map(NotificationRule::getId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(firstMembersAlert.getId(), secondMembersAlert.getId()), householdAlertIds);
    }
}
