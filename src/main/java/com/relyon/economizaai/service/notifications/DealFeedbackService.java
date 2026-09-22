package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.repository.NotificationEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * First consumer of the notification telemetry: turns a user's own
 * DISMISSED / MUTED signals into a per-user deal-suppression set.
 *
 * <p>Rules (deterministic, no volume needed — one user's own events suffice):
 * <ul>
 *   <li>MUTED product → suppress that product everywhere for {@code muted-days}.</li>
 *   <li>DISMISSED product at a market → suppress that (product, market) pair
 *       for {@code dismissed-days}; without a market, suppress the product
 *       everywhere for the same window.</li>
 * </ul>
 *
 * <p>Scope is the individual user (events are per-user): one member dismissing
 * a deal never hides it from the rest of the household.
 *
 * <p>Rollout is governed by {@link RelevanceMode} — see the enum and the
 * relevance report ({@code GET /admin/notifications/relevance-report}) for the
 * SHADOW→ON validation path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealFeedbackService {

    private final NotificationEventRepository eventRepository;

    @Value("${economizaai.notifications.relevance.mode:SHADOW}")
    private RelevanceMode mode;

    @Value("${economizaai.notifications.relevance.dismissed-days:14}")
    private int dismissedDays;

    @Value("${economizaai.notifications.relevance.muted-days:180}")
    private int mutedDays;

    public RelevanceMode mode() {
        return mode;
    }

    public int dismissedDays() {
        return dismissedDays;
    }

    public int mutedDays() {
        return mutedDays;
    }

    /** The user's current suppression set; empty (and query-free) when mode is OFF. */
    public SuppressionSet suppressionsFor(User user) {
        if (mode == RelevanceMode.OFF) return SuppressionSet.empty();
        var now = OffsetDateTime.now();
        var horizon = now.minusDays(Math.max(dismissedDays, mutedDays));
        var signals = eventRepository.findFeedbackSignals(user.getId(), horizon);
        if (signals.isEmpty()) return SuppressionSet.empty();

        var mutedProducts = new HashSet<UUID>();
        var dismissedProducts = new HashSet<UUID>();
        var dismissedPairs = new HashMap<UUID, Set<String>>();
        var mutedCutoff = now.minusDays(mutedDays);
        var dismissedCutoff = now.minusDays(dismissedDays);
        for (var signal : signals) {
            if (signal.getEventType() == NotificationEventType.MUTED
                    && !signal.getOccurredAt().isBefore(mutedCutoff)) {
                mutedProducts.add(signal.getProductId());
            } else if (signal.getEventType() == NotificationEventType.DISMISSED
                    && !signal.getOccurredAt().isBefore(dismissedCutoff)) {
                if (signal.getMarketCnpj() != null) {
                    dismissedPairs.computeIfAbsent(signal.getProductId(), productId -> new HashSet<>())
                            .add(signal.getMarketCnpj());
                } else {
                    dismissedProducts.add(signal.getProductId());
                }
            }
        }
        return new SuppressionSet(Set.copyOf(mutedProducts), Set.copyOf(dismissedProducts),
                Map.copyOf(dismissedPairs));
    }

    /** Immutable view of what the user's feedback currently suppresses. */
    public record SuppressionSet(Set<UUID> mutedProducts,
                                 Set<UUID> dismissedProducts,
                                 Map<UUID, Set<String>> dismissedPairs) {

        private static final SuppressionSet EMPTY = new SuppressionSet(Set.of(), Set.of(), Map.of());

        public static SuppressionSet empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return mutedProducts.isEmpty() && dismissedProducts.isEmpty() && dismissedPairs.isEmpty();
        }

        /** Product suppressed regardless of market (muted, or dismissed market-wide). */
        public boolean suppressesProduct(UUID productId) {
            return mutedProducts.contains(productId) || dismissedProducts.contains(productId);
        }

        public boolean suppresses(UUID productId, String marketCnpj) {
            if (suppressesProduct(productId)) return true;
            var markets = dismissedPairs.get(productId);
            return markets != null && markets.contains(marketCnpj);
        }
    }
}
