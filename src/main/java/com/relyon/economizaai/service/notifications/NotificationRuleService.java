package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.CreateNotificationRuleRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationRuleRequest;
import com.relyon.economizaai.dto.response.NotificationRuleResponse;
import com.relyon.economizaai.exception.InvalidNotificationRuleException;
import com.relyon.economizaai.exception.NotificationRuleNotFoundException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.Product;
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
 * CRUD + lifecycle for {@link NotificationRule}s — the unified create/list/
 * enable/disable surface for both user-created rules and system defaults.
 *
 * <p>System defaults are lazily seeded (one active rule per default type) so
 * the user sees a single list of toggles. Triggers consult {@link #isEnabled}
 * to honor a disabled default without needing a backfill migration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRuleService {

    private static final int DEFAULT_LEAD_TIME_DAYS = 3;

    private final NotificationRuleRepository ruleRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional
    public NotificationRuleResponse create(User user, CreateNotificationRuleRequest request) {
        var type = request.type();
        if (!type.isUserCreatable()) {
            throw new InvalidNotificationRuleException(type + " is a system default — toggle it instead of creating one");
        }
        var product = resolveAndValidate(type, request);
        var productId = product != null ? product.getId() : null;

        var rule = ruleRepository.findByUserIdAndTypeAndProductId(user.getId(), type, productId)
                .map(existing -> applyCreate(existing, request, product))
                .orElseGet(() -> applyCreate(NotificationRule.builder()
                        .user(user).type(type).isDefault(false).build(), request, product));
        var saved = ruleRepository.save(rule);
        log.info("notification_rule.saved user={} type={} product={} active={}",
                LogMasker.email(user.getEmail()), type, productId, saved.isActive());
        return NotificationRuleResponse.from(saved,
                householdProductAliasService.findFor(user.getHousehold(), saved.getProduct()));
    }

    private NotificationRule applyCreate(NotificationRule rule, CreateNotificationRuleRequest request, Product product) {
        rule.setProduct(product);
        rule.setThresholdPrice(request.thresholdPrice());
        rule.setRadiusKm(request.radiusKm());
        rule.setChannel(request.channel());
        rule.setActive(request.active() == null || request.active());
        if (rule.getType() == NotificationType.STOCKOUT) {
            rule.setLeadTimeDays(request.leadTimeDays() != null ? request.leadTimeDays() : DEFAULT_LEAD_TIME_DAYS);
        }
        return rule;
    }

    private Product resolveAndValidate(NotificationType type, CreateNotificationRuleRequest request) {
        if (type.requiresThreshold() && request.thresholdPrice() == null) {
            throw new InvalidNotificationRuleException(type + " requires a thresholdPrice");
        }
        if (!type.requiresProduct()) {
            return null;
        }
        if (request.productId() == null) {
            throw new InvalidNotificationRuleException(type + " requires a productId");
        }
        return productRepository.findById(request.productId()).orElseThrow(ProductNotFoundException::new);
    }

    @Transactional
    public List<NotificationRuleResponse> list(User user) {
        ensureDefaults(user);
        var rules = ruleRepository.findAllByUserIdFetchProduct(user.getId());
        var friendlyNames = householdProductAliasService.friendlyNamesFor(user.getHousehold().getId(),
                rules.stream()
                        .filter(rule -> rule.getProduct() != null)
                        .map(rule -> rule.getProduct().getId())
                        .distinct()
                        .toList());
        return rules.stream()
                .map(rule -> NotificationRuleResponse.from(rule,
                        rule.getProduct() != null ? friendlyNames.get(rule.getProduct().getId()) : null))
                .toList();
    }

    @Transactional
    public NotificationRuleResponse update(User user, UUID id, UpdateNotificationRuleRequest request) {
        var rule = ruleRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(NotificationRuleNotFoundException::new);
        if (request.active() != null) rule.setActive(request.active());
        if (request.thresholdPrice() != null) rule.setThresholdPrice(request.thresholdPrice());
        if (request.radiusKm() != null) rule.setRadiusKm(request.radiusKm());
        if (request.leadTimeDays() != null) rule.setLeadTimeDays(request.leadTimeDays());
        if (request.channel() != null) rule.setChannel(request.channel());
        var saved = ruleRepository.save(rule);
        log.info("notification_rule.updated user={} rule={} active={}",
                LogMasker.email(user.getEmail()), id, saved.isActive());
        return NotificationRuleResponse.from(saved,
                householdProductAliasService.findFor(user.getHousehold(), saved.getProduct()));
    }

    @Transactional
    public void delete(User user, UUID id) {
        var rule = ruleRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(NotificationRuleNotFoundException::new);
        if (rule.isDefault()) {
            throw new InvalidNotificationRuleException("system defaults cannot be deleted — disable them instead");
        }
        ruleRepository.delete(rule);
        log.info("notification_rule.deleted user={} rule={}", LogMasker.email(user.getEmail()), id);
    }

    /** Materializes any missing default rules for the user (idempotent, active by default). */
    @Transactional
    public void ensureDefaults(User user) {
        for (var type : NotificationType.defaults()) {
            if (ruleRepository.findByUserIdAndTypeAndProductIsNull(user.getId(), type).isEmpty()) {
                ruleRepository.save(NotificationRule.builder()
                        .user(user).type(type).isDefault(true).active(true).build());
            }
        }
    }

    /**
     * Whether a default-type notification is enabled for the user. Absent
     * (never seeded) counts as enabled — defaults are on until explicitly
     * disabled, so triggers work even before {@link #ensureDefaults} runs.
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(User user, NotificationType type) {
        return ruleRepository.findByUserIdAndTypeAndProductIsNull(user.getId(), type)
                .map(NotificationRule::isActive)
                .orElse(true);
    }
}
