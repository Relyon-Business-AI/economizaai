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

        var rule = ruleRepository
                .findByUserIdAndTypeAndProductId(user.getId(), NotificationType.PRICE_DROP, product.getId())
                .orElseGet(() -> NotificationRule.builder()
                        .user(user).type(NotificationType.PRICE_DROP).product(product)
                        .isDefault(false).build());
        rule.setThresholdPrice(request.thresholdPrice());
        rule.setRadiusKm(request.radiusKm());
        rule.setActive(active);
        var saved = ruleRepository.save(rule);
        log.info("price_alert.saved user={} product={} threshold={} radiusKm={}",
                LogMasker.email(user.getEmail()), product.getId(), saved.getThresholdPrice(), saved.getRadiusKm());
        return PriceAlertResponse.from(saved,
                householdProductAliasService.findFor(user.getHousehold(), saved.getProduct()));
    }

    @Transactional(readOnly = true)
    public List<PriceAlertResponse> list(User user) {
        var priceDropRules = ruleRepository.findAllByUserIdFetchProduct(user.getId()).stream()
                .filter(rule -> rule.getType() == NotificationType.PRICE_DROP)
                .toList();
        var friendlyNames = householdProductAliasService.friendlyNamesFor(user.getHousehold().getId(),
                priceDropRules.stream()
                        .filter(rule -> rule.getProduct() != null)
                        .map(rule -> rule.getProduct().getId())
                        .distinct()
                        .toList());
        return priceDropRules.stream()
                .map(rule -> PriceAlertResponse.from(rule,
                        rule.getProduct() != null ? friendlyNames.get(rule.getProduct().getId()) : null))
                .toList();
    }

    @Transactional
    public void delete(User user, UUID id) {
        var rule = ruleRepository.findByIdAndUserId(id, user.getId())
                .filter(existing -> existing.getType() == NotificationType.PRICE_DROP)
                .orElseThrow(PriceAlertNotFoundException::new);
        ruleRepository.delete(rule);
        log.info("price_alert.deleted user={} alert={}", LogMasker.email(user.getEmail()), id);
    }
}
