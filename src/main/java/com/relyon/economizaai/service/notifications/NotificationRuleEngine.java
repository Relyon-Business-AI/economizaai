package com.relyon.economizaai.service.notifications;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evaluates freshly-written {@link PriceObservation}s against the user's explicit
 * product alerts — runs on the collaborative-index write path so one household's
 * confirmed receipt can fire another household's notification.
 *
 * <p>Handles only the explicit user alert:
 * <ul>
 *   <li><b>PRICE_DROP</b> — user rule, fire when observed price ≤ threshold.</li>
 * </ul>
 *
 * <p>Discovery notifications (PROMO_COMMUNITY / PROMO_PERSONAL / CHEAPER_MARKET) are
 * no longer fired in real time — they are delivered through the daily digest
 * ({@code DealsService} / {@code DealsDigestScheduler}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRuleEngine {

    /** Don't fire the same rule more than once within this window. */
    private static final Duration COOLDOWN = Duration.ofHours(24);

    private final NotificationRuleRepository ruleRepository;
    private final LocalizedMessageService messageService;
    private final MarketLocationService marketLocationService;
    private final MarketNameService marketNameService;
    private final NotificationService notificationService;

    @Transactional
    public void evaluate(List<PriceObservation> observations, UUID contributingHouseholdId) {
        if (observations == null || observations.isEmpty()) return;

        var productIds = observations.stream().map(observation -> observation.getProduct().getId()).collect(Collectors.toSet());
        var cnpjs = observations.stream().map(PriceObservation::getMarketCnpj).distinct().toList();
        var locations = marketLocationService.findByCnpjs(cnpjs);
        var now = LocalDateTime.now();

        var fired = evaluateUserPriceRules(observations, productIds, locations, contributingHouseholdId, now);

        if (!fired.isEmpty()) {
            ruleRepository.saveAll(fired);
            log.info("notification_rule.evaluate done observations={} fired={}", observations.size(), fired.size());
        }
    }

    // ---- user price rules: PRICE_DROP (<=) ----

    private List<NotificationRule> evaluateUserPriceRules(List<PriceObservation> observations,
                                                          Set<UUID> productIds,
                                                          Map<String, MarketLocation> locations,
                                                          UUID contributingHouseholdId,
                                                          LocalDateTime now) {
        var rules = ruleRepository.findActiveProductRules(
                List.of(NotificationType.PRICE_DROP), productIds);
        if (rules.isEmpty()) return List.of();

        var rulesByProduct = rules.stream().collect(Collectors.groupingBy(rule -> rule.getProduct().getId()));
        var firedIds = new HashSet<UUID>();
        var fired = new ArrayList<NotificationRule>();

        for (var observation : observations) {
            var candidates = rulesByProduct.get(observation.getProduct().getId());
            if (candidates == null) continue;
            for (var rule : candidates) {
                if (firedIds.contains(rule.getId())) continue;
                if (!priceRuleShouldFire(rule, observation, contributingHouseholdId, locations, now)) continue;

                notifyPriceRule(rule, observation);
                rule.setLastFiredAt(now);
                firedIds.add(rule.getId());
                fired.add(rule);
            }
        }
        return fired;
    }

    /** All the gates a price rule must clear before firing for this observation. */
    private boolean priceRuleShouldFire(NotificationRule rule, PriceObservation observation,
                                        UUID contributingHouseholdId,
                                        Map<String, MarketLocation> locations, LocalDateTime now) {
        if (sameHousehold(rule, contributingHouseholdId)) return false;
        if (!thresholdMet(rule, observation.getUnitPrice())) return false;
        if (inCooldown(rule, now)) return false;
        return withinRadius(rule.getRadiusKm(), rule.getUser(), observation, locations);
    }

    private boolean thresholdMet(NotificationRule rule, BigDecimal unitPrice) {
        var threshold = rule.getThresholdPrice();
        if (threshold == null) return false;
        return unitPrice.compareTo(threshold) <= 0;
    }

    private void notifyPriceRule(NotificationRule rule, PriceObservation observation) {
        var productName = observation.getProduct().getNormalizedName();
        var marketName = friendlyMarketName(rule, observation);
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        var title = messageService.translate("notification.price_drop.title", locale, productName);
        var body = messageService.translate("notification.price_drop.body", locale,
                productName, observation.getUnitPrice().toString(), marketName, rule.getThresholdPrice().toString());
        notificationService.notify(new NotificationPayload(
                rule.getUser(), rule.getType(), title, body, baseExtras(rule, observation)));
    }

    // ---- shared helpers ----

    private boolean sameHousehold(NotificationRule rule, UUID contributingHouseholdId) {
        return contributingHouseholdId != null
                && rule.getUser().getHousehold() != null
                && contributingHouseholdId.equals(rule.getUser().getHousehold().getId());
    }

    private boolean inCooldown(NotificationRule rule, LocalDateTime now) {
        return rule.getLastFiredAt() != null && rule.getLastFiredAt().isAfter(now.minus(COOLDOWN));
    }

    private boolean withinRadius(Double radiusKm, User user,
                                 PriceObservation observation, Map<String, MarketLocation> locations) {
        if (radiusKm == null) return true;
        var location = locations.get(observation.getMarketCnpj());
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null
                || location == null || !location.hasCoordinates()) {
            return false; // radius requested but we can't place it — honor the constraint
        }
        var km = DistanceCalculator.kmBetween(
                user.getHomeLatitude(), user.getHomeLongitude(),
                location.getLatitude(), location.getLongitude());
        return km <= radiusKm;
    }

    private String marketName(PriceObservation observation) {
        return observation.getMarketName() != null ? observation.getMarketName() : "um mercado próximo";
    }

    /** The rule owner's household custom name for the market, falling back to the observation's display name. */
    private String friendlyMarketName(NotificationRule rule, PriceObservation observation) {
        var householdId = rule.getUser().getHousehold() != null ? rule.getUser().getHousehold().getId() : null;
        return marketNameService.resolve(householdId, observation.getMarketCnpj(), marketName(observation));
    }

    private Map<String, Object> baseExtras(NotificationRule rule, PriceObservation observation) {
        var householdId = rule.getUser().getHousehold() != null ? rule.getUser().getHousehold().getId() : null;
        var friendly = marketNameService.resolve(householdId, observation.getMarketCnpj(), observation.getMarketName());
        var extras = new HashMap<String, Object>();
        extras.put("ruleId", rule.getId().toString());
        extras.put("productId", observation.getProduct().getId().toString());
        extras.put("observedPrice", observation.getUnitPrice());
        extras.put("marketCnpj", observation.getMarketCnpj());
        extras.put("marketName", observation.getMarketName() != null ? observation.getMarketName() : "");
        extras.put("marketFriendlyName", friendly != null ? friendly : "");
        if (rule.getThresholdPrice() != null) extras.put("thresholdPrice", rule.getThresholdPrice());
        return extras;
    }
}
