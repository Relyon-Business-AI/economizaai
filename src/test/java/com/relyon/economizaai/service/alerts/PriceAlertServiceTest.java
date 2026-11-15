package com.relyon.economizaai.service.alerts;

import com.relyon.economizaai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizaai.exception.PriceAlertNotFoundException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceAlertServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @InjectMocks private PriceAlertService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    private static final Household HOUSEHOLD = Household.builder().id(UUID.randomUUID()).build();

    /** A member of the one household these tests use. */
    private User user() {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(HOUSEHOLD)
                .build();
    }

    private NotificationRule alert(User owner, Product product, String thresholdPrice) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(owner).type(NotificationType.PRICE_DROP).product(product)
                .thresholdPrice(new BigDecimal(thresholdPrice)).radiusKm(20.0).active(true).build();
    }

    private void householdHasAlerts(NotificationRule... newestFirst) {
        when(ruleRepository.findAllByHouseholdIdAndTypeFetchProduct(HOUSEHOLD.getId(), NotificationType.PRICE_DROP))
                .thenReturn(List.of(newestFirst));
    }

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite Italac 1L").build();
    }

    // ---- create ----

    @Test
    void create_persistsNewPriceDropRule() {
        var owner = user();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        householdHasAlerts();
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(owner,
                new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 5.0, null));

        assertEquals(new BigDecimal("6.00"), response.thresholdPrice());
        assertEquals(5.0, response.radiusKm());
        assertTrue(response.active());
    }

    @Test
    void create_updatesExistingRuleInPlace() {
        var owner = user();
        var existing = alert(owner, product(), "9.99");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        householdHasAlerts(existing);
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(owner,
                new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 3.0, false));

        assertEquals(new BigDecimal("6.00"), existing.getThresholdPrice());
        assertEquals(3.0, existing.getRadiusKm());
        assertFalse(existing.isActive());
        assertFalse(response.active());
    }

    @Test
    void create_updatesTheAlertAnotherMemberCreatedInsteadOfAddingASecondOne() {
        var otherMembersAlert = alert(user(), product(), "9.99");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        householdHasAlerts(otherMembersAlert);
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(user(), new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 3.0, null));

        verify(ruleRepository).save(otherMembersAlert);
        assertEquals(new BigDecimal("6.00"), otherMembersAlert.getThresholdPrice());
    }

    @Test
    void create_leavesOneAlertWhenTwoMembersHadOneOnTheSameProduct() {
        var newestAlert = alert(user(), product(), "9.99");
        var olderAlert = alert(user(), product(), "8.00");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        householdHasAlerts(newestAlert, olderAlert);
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(user(), new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), null, null));

        verify(ruleRepository).save(newestAlert);
        verify(ruleRepository).deleteAll(List.of(olderAlert));
    }

    @Test
    void create_unknownProductThrows() {
        var owner = user();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        var request = new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), null, null);

        assertThrows(ProductNotFoundException.class, () -> service.create(owner, request));
    }

    // ---- list ----

    @Test
    void list_showsTheAlertsOfEveryMemberOfTheHousehold() {
        var otherProduct = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz 5kg").build();
        householdHasAlerts(alert(user(), product(), "4.00"), alert(user(), otherProduct, "20.00"));

        var responses = service.list(user());

        assertEquals(2, responses.size());
        assertEquals(new BigDecimal("4.00"), responses.get(0).thresholdPrice());
        assertEquals(new BigDecimal("20.00"), responses.get(1).thresholdPrice());
    }

    @Test
    void list_showsOneAlertPerProductKeepingTheMostRecentlyChanged() {
        householdHasAlerts(alert(user(), product(), "4.00"), alert(user(), product(), "5.50"));

        var responses = service.list(user());

        assertEquals(1, responses.size());
        assertEquals(new BigDecimal("4.00"), responses.get(0).thresholdPrice());
    }

    @Test
    void list_carriesHouseholdFriendlyName() {
        var user = user();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ 5KG").build();
        householdHasAlerts(alert(user, product, "20.00"));
        when(householdProductAliasService.friendlyNamesFor(eq(HOUSEHOLD.getId()), any()))
                .thenReturn(Map.of(product.getId(), "Arroz de todo dia"));

        var alerts = service.list(user);

        assertEquals("ARROZ 5KG", alerts.get(0).productName());
        assertEquals("Arroz de todo dia", alerts.get(0).friendlyDescription());
    }

    // ---- delete ----

    @Test
    void delete_removesPriceDropRuleWhenFound() {
        var owner = user();
        var existing = alert(owner, product(), "4.00");
        householdHasAlerts(existing);

        service.delete(owner, existing.getId());

        verify(ruleRepository).deleteAll(List.of(existing));
    }

    @Test
    void delete_removesAnAlertAnotherMemberCreatedAndItsRepeatsOnTheSameProduct() {
        var otherProduct = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz 5kg").build();
        var otherMembersAlert = alert(user(), product(), "4.00");
        var repeatedAlert = alert(user(), product(), "5.50");
        var alertOnOtherProduct = alert(user(), otherProduct, "20.00");
        householdHasAlerts(otherMembersAlert, repeatedAlert, alertOnOtherProduct);

        service.delete(user(), otherMembersAlert.getId());

        verify(ruleRepository).deleteAll(List.of(otherMembersAlert, repeatedAlert));
    }

    @Test
    void delete_unknownAlertThrows() {
        var owner = user();
        householdHasAlerts(alert(owner, product(), "4.00"));
        var unknownAlertId = UUID.randomUUID();

        assertThrows(PriceAlertNotFoundException.class, () -> service.delete(owner, unknownAlertId));
        verify(ruleRepository, never()).deleteAll(any());
    }
}
