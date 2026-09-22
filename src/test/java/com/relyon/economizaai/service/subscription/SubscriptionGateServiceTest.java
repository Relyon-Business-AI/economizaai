package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionGateServiceTest {

    private SubscriptionGateService gate;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        // Enforcement is OFF by default (paywall dormant); these tests exercise the
        // ENFORCED behavior, so turn it on. free defaults: watchedMarkets=3, historyDays=90, monthlyReceipts=5
        properties.getSubscription().setEnforce(true);
        gate = new SubscriptionGateService(properties);
    }

    @Test
    void enforcementOff_allowsEverythingForFreeUsers() {
        var properties = new CollaborativeProperties(); // enforce defaults to false
        var dormant = new SubscriptionGateService(properties);
        var free = user(SubscriptionTier.FREE);

        for (var feature : Feature.values()) {
            assertTrue(dormant.allows(free, feature), "dormant gate should allow " + feature);
        }
        assertEquals(Integer.MAX_VALUE, dormant.watchedMarketLimit(free));
        assertEquals(Integer.MAX_VALUE, dormant.monthlyReceiptLimit(free));
        assertNull(dormant.freeHistoryWindowDays(free));
        var old = LocalDateTime.now().minusYears(3);
        assertEquals(old, dormant.clampFrom(free, old));
        dormant.require(free, Feature.BASKET_OPTIMIZATION); // no exception
    }

    private User user(SubscriptionTier tier) {
        return User.builder().id(UUID.randomUUID()).email("u@e").subscriptionTier(tier).build();
    }

    @Test
    void allows_proGetsEveryFeature() {
        var pro = user(SubscriptionTier.PRO);
        for (var feature : Feature.values()) {
            assertTrue(gate.allows(pro, feature), "PRO should allow " + feature);
        }
    }

    @Test
    void allows_freeBlockedFromEveryFeature() {
        var free = user(SubscriptionTier.FREE);
        for (var feature : Feature.values()) {
            assertFalse(gate.allows(free, feature), "FREE should be blocked from " + feature);
        }
    }

    @Test
    void require_throwsForFreeAndPassesForPro() {
        var freeUser = user(SubscriptionTier.FREE);
        assertThrows(PaywallException.class,
                () -> gate.require(freeUser, Feature.BASKET_OPTIMIZATION));
        // PRO: no exception
        gate.require(user(SubscriptionTier.PRO), Feature.BASKET_OPTIMIZATION);
    }

    @Test
    void watchedMarketLimit_freeUsesConfigProUnlimited() {
        assertEquals(3, gate.watchedMarketLimit(user(SubscriptionTier.FREE)));
        assertEquals(Integer.MAX_VALUE, gate.watchedMarketLimit(user(SubscriptionTier.PRO)));
    }

    @Test
    void monthlyReceiptLimit_freeUsesConfigProUnlimited() {
        assertEquals(5, gate.monthlyReceiptLimit(user(SubscriptionTier.FREE)));
        assertEquals(Integer.MAX_VALUE, gate.monthlyReceiptLimit(user(SubscriptionTier.PRO)));
    }

    @Test
    void freeHistoryWindowDays_freeUsesConfigProNull() {
        assertEquals(90, gate.freeHistoryWindowDays(user(SubscriptionTier.FREE)));
        assertNull(gate.freeHistoryWindowDays(user(SubscriptionTier.PRO)));
    }

    @Test
    void clampFrom_proIsUnaffected() {
        var pro = user(SubscriptionTier.PRO);
        var old = LocalDateTime.now().minusYears(3);
        assertEquals(old, gate.clampFrom(pro, old));
        assertNull(gate.clampFrom(pro, null));
    }

    @Test
    void clampFrom_freeFloorsEarlierRequests() {
        var free = user(SubscriptionTier.FREE);
        var floor = LocalDateTime.now().minusDays(90);

        var clampedOld = gate.clampFrom(free, LocalDateTime.now().minusYears(2));
        assertFalse(clampedOld.isBefore(floor.minusSeconds(2)));
        assertTrue(clampedOld.isAfter(floor.minusSeconds(2)));

        // Null request (unbounded) is floored to the window.
        var clampedNull = gate.clampFrom(free, null);
        assertTrue(clampedNull.isAfter(floor.minusSeconds(2)));
    }

    @Test
    void clampFrom_freeKeepsRecentRequestWithinWindow() {
        var free = user(SubscriptionTier.FREE);
        var recent = LocalDateTime.now().minusDays(10);
        assertEquals(recent, gate.clampFrom(free, recent));
    }
}
