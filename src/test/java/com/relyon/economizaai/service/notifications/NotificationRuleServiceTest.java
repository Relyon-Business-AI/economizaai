package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.CreateNotificationRuleRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationRuleRequest;
import com.relyon.economizaai.exception.InvalidNotificationRuleException;
import com.relyon.economizaai.exception.NotificationRuleNotFoundException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRuleServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @InjectMocks private NotificationRuleService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite").build();
    }

    // ---- create ----

    @Test
    void create_persistsUserScopedPriceDropRule() {
        var owner = user();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(ruleRepository.findByUserIdAndTypeAndProductId(owner.getId(), NotificationType.PRICE_DROP, PRODUCT_ID))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(owner, new CreateNotificationRuleRequest(
                NotificationType.PRICE_DROP, PRODUCT_ID, new BigDecimal("6.00"), null, null, null, null));

        assertEquals(NotificationType.PRICE_DROP, response.type());
        assertEquals(new BigDecimal("6.00"), response.thresholdPrice());
        assertTrue(response.active());
    }

    @Test
    void create_rejectsDefaultScopeType() {
        var owner = user();

        var request = new CreateNotificationRuleRequest(
                NotificationType.DIGEST, null, null, null, null, null, null);
        assertThrows(InvalidNotificationRuleException.class, () -> service.create(owner, request));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_rejectsMissingThresholdForPriceDrop() {
        var owner = user();

        var request = new CreateNotificationRuleRequest(
                NotificationType.PRICE_DROP, PRODUCT_ID, null, null, null, null, null);
        assertThrows(InvalidNotificationRuleException.class, () -> service.create(owner, request));
    }

    @Test
    void create_rejectsMissingProductForStockout() {
        var owner = user();

        var request = new CreateNotificationRuleRequest(
                NotificationType.STOCKOUT, null, null, null, null, null, null);
        assertThrows(InvalidNotificationRuleException.class, () -> service.create(owner, request));
    }

    @Test
    void create_unknownProductThrows() {
        var owner = user();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        var request = new CreateNotificationRuleRequest(
                NotificationType.PRICE_DROP, PRODUCT_ID, new BigDecimal("6.00"), null, null, null, null);
        assertThrows(ProductNotFoundException.class, () -> service.create(owner, request));
    }

    // ---- update ----

    @Test
    void update_togglesActiveFlag() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        var existing = NotificationRule.builder().id(ruleId)
                .user(owner).type(NotificationType.PRICE_DROP).product(product())
                .active(true).build();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.of(existing));
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.update(owner, ruleId,
                new UpdateNotificationRuleRequest(false, null, null, null, null));

        assertFalse(response.active());
        assertFalse(existing.isActive());
    }

    @Test
    void update_unknownRuleThrows() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.empty());
        var request = new UpdateNotificationRuleRequest(false, null, null, null, null);

        assertThrows(NotificationRuleNotFoundException.class, () -> service.update(owner, ruleId, request));
    }

    // ---- delete ----

    @Test
    void delete_rejectsSystemDefaultRule() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        var defaultRule = NotificationRule.builder().id(ruleId)
                .user(owner).type(NotificationType.DIGEST).isDefault(true).active(true).build();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.of(defaultRule));

        assertThrows(InvalidNotificationRuleException.class, () -> service.delete(owner, ruleId));
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    void delete_removesUserRuleWhenFound() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        var rule = NotificationRule.builder().id(ruleId)
                .user(owner).type(NotificationType.PRICE_DROP).isDefault(false).active(true).build();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.of(rule));

        service.delete(owner, ruleId);

        verify(ruleRepository).delete(rule);
    }

    @Test
    void delete_unknownRuleThrows() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.empty());

        assertThrows(NotificationRuleNotFoundException.class, () -> service.delete(owner, ruleId));
    }

    // ---- ensureDefaults ----

    @Test
    void ensureDefaults_seedsOnlyMissingDefaults() {
        var owner = user();
        // CHEAPER_MARKET already seeded; the rest are missing.
        when(ruleRepository.findByUserIdAndTypeAndProductIsNull(eq(owner.getId()), any()))
                .thenAnswer(invocation -> {
                    NotificationType type = invocation.getArgument(1);
                    return type == NotificationType.CHEAPER_MARKET
                            ? Optional.of(NotificationRule.builder().id(UUID.randomUUID())
                                    .user(owner).type(type).isDefault(true).build())
                            : Optional.empty();
                });

        service.ensureDefaults(owner);

        var defaultCount = NotificationType.defaults().size();
        var saveCaptor = ArgumentCaptor.forClass(NotificationRule.class);
        verify(ruleRepository, times(defaultCount - 1)).save(saveCaptor.capture());
        assertTrue(saveCaptor.getAllValues().stream().allMatch(NotificationRule::isDefault));
        assertTrue(saveCaptor.getAllValues().stream()
                .noneMatch(rule -> rule.getType() == NotificationType.CHEAPER_MARKET));
    }

    // ---- isEnabled ----

    @Test
    void isEnabled_absentRuleDefaultsToTrue() {
        var owner = user();
        when(ruleRepository.findByUserIdAndTypeAndProductIsNull(owner.getId(), NotificationType.PROMO_PERSONAL))
                .thenReturn(Optional.empty());

        assertTrue(service.isEnabled(owner, NotificationType.PROMO_PERSONAL));
    }

    @Test
    void isEnabled_presentRuleUsesActiveFlag() {
        var owner = user();
        var disabled = NotificationRule.builder().id(UUID.randomUUID())
                .user(owner).type(NotificationType.PROMO_PERSONAL).isDefault(true).active(false).build();
        when(ruleRepository.findByUserIdAndTypeAndProductIsNull(owner.getId(), NotificationType.PROMO_PERSONAL))
                .thenReturn(Optional.of(disabled));

        assertFalse(service.isEnabled(owner, NotificationType.PROMO_PERSONAL));
    }

    @Test
    void list_carriesHouseholdFriendlyNameForProductRules() {
        var user = user();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("CAFE 500G").build();
        var rule = NotificationRule.builder().id(UUID.randomUUID()).user(user)
                .type(NotificationType.PRICE_DROP).product(product).active(true).build();
        when(ruleRepository.findAllByUserIdFetchProduct(user.getId())).thenReturn(List.of(rule));
        when(householdProductAliasService.friendlyNamesFor(eq(user.getHousehold().getId()), any()))
                .thenReturn(Map.of(product.getId(), "Cafe do papai"));

        var rules = service.list(user);

        assertEquals("CAFE 500G", rules.get(0).productName());
        assertEquals("Cafe do papai", rules.get(0).friendlyDescription());
    }
}
