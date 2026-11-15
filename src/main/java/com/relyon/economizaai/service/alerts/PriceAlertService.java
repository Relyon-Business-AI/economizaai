package com.relyon.economizaai.service.alerts;

import com.relyon.economizaai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizaai.dto.response.PriceAlertResponse;
import com.relyon.economizaai.exception.PriceAlertNotFoundException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Backward-compatible "avise-me quando" surface for {@code /api/v1/alerts}.
 * A price alert is just a {@link NotificationType#PRICE_DROP} notification
 * rule — this adapter keeps the legacy endpoints working over the unified
 * {@link NotificationRule} store. New rule types live behind
 * {@code /api/v1/notification-rules}; write-path evaluation lives in
 * {@link com.relyon.economizaai.service.notifications.NotificationRuleEngine}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertService {

    private final NotificationRuleRepository ruleRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional
    public PriceAlertResponse create(User user, CreatePriceAlertRequest request) {
        var product = productRepository.findById(request.productId())
                .orElseThrow(ProductNotFoundException::new);
        var active = request.active() == null || request.active();

        var alertsOnProduct = householdAlerts(user).stream()
                .filter(rule -> isOnProduct(rule, product.getId()))
                .toList();
        var rule = alertsOnProduct.isEmpty()
                ? NotificationRule.builder()
                        .user(user).type(NotificationType.PRICE_DROP).product(product)
                        .isDefault(false).build()
                : alertsOnProduct.get(0);
        rule.setThresholdPrice(request.thresholdPrice());
        rule.setRadiusKm(request.radiusKm());
        rule.setActive(active);
        var saved = ruleRepository.save(rule);
        deleteRepeatedAlerts(alertsOnProduct);
        log.info("price_alert.saved user={} product={} threshold={} radiusKm={}",
                LogMasker.email(user.getEmail()), product.getId(), saved.getThresholdPrice(), saved.getRadiusKm());
        return PriceAlertResponse.from(saved,
                householdProductAliasService.findFor(user.getHousehold(), saved.getProduct()));
    }

    /** The household's price alerts, one per product (the most recently changed wins). */
    @Transactional(readOnly = true)
    public List<PriceAlertResponse> list(User user) {
        var alertPerProduct = newestAlertPerProduct(householdAlerts(user));
        var friendlyNames = householdProductAliasService.friendlyNamesFor(user.getHousehold().getId(),
                alertPerProduct.stream()
                        .filter(rule -> rule.getProduct() != null)
                        .map(rule -> rule.getProduct().getId())
                        .distinct()
                        .toList());
        return alertPerProduct.stream()
                .map(rule -> PriceAlertResponse.from(rule,
                        rule.getProduct() != null ? friendlyNames.get(rule.getProduct().getId()) : null))
                .toList();
    }

    /** Any member may delete a household alert; every alert of the household on that product goes with it. */
    @Transactional
    public void delete(User user, UUID id) {
        var householdAlerts = householdAlerts(user);
        var alert = householdAlerts.stream()
                .filter(rule -> rule.getId().equals(id))
                .findFirst()
                .orElseThrow(PriceAlertNotFoundException::new);
        var alertsOnSameProduct = alert.getProduct() == null
                ? List.of(alert)
                : householdAlerts.stream()
                        .filter(rule -> isOnProduct(rule, alert.getProduct().getId()))
                        .toList();
        ruleRepository.deleteAll(alertsOnSameProduct);
        log.info("price_alert.deleted user={} alert={} removed={}",
                LogMasker.email(user.getEmail()), id, alertsOnSameProduct.size());
    }

    /** Price alerts created by any member of the user's household, most recently changed first. */
    private List<NotificationRule> householdAlerts(User user) {
        return ruleRepository.findAllByHouseholdIdAndTypeFetchProduct(
                user.getHousehold().getId(), NotificationType.PRICE_DROP);
    }

    private boolean isOnProduct(NotificationRule rule, UUID productId) {
        return rule.getProduct() != null && rule.getProduct().getId().equals(productId);
    }

    /** Two members may each have created an alert on the same product before alerts were shared. */
    private List<NotificationRule> newestAlertPerProduct(List<NotificationRule> newestFirst) {
        var seenProductIds = new HashSet<UUID>();
        return newestFirst.stream()
                .filter(rule -> rule.getProduct() == null || seenProductIds.add(rule.getProduct().getId()))
                .toList();
    }

    private void deleteRepeatedAlerts(List<NotificationRule> alertsOnProduct) {
        if (alertsOnProduct.size() > 1) {
            ruleRepository.deleteAll(alertsOnProduct.subList(1, alertsOnProduct.size()));
        }
    }
}
