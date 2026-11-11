package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
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
        var floor = BrazilClock.nowDateTime().minusDays(90);

        var clampedOld = gate.clampFrom(free, BrazilClock.nowDateTime().minusYears(2));
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

    // ---------- per-feature policy ----------

    @Test
    void allows_freeGetsOnlyTheFeaturesLeftOpenByConfig() {
        var properties = new CollaborativeProperties();
        properties.getSubscription().setEnforce(true);
        properties.getSubscription().getFree().setOpenFeatures(
                EnumSet.of(Feature.PUSH_AND_EMAIL_DELIVERY, Feature.RECEIPT_UPLOAD_UNLIMITED));
        var configured = new SubscriptionGateService(properties);
        var free = user(SubscriptionTier.FREE);

        assertTrue(configured.allows(free, Feature.PUSH_AND_EMAIL_DELIVERY));
        assertFalse(configured.allows(free, Feature.BASKET_OPTIMIZATION));
        assertFalse(configured.allows(free, Feature.CSV_EXPORT));
        // An open metered feature has no cap; the other one keeps its own.
        assertEquals(Integer.MAX_VALUE, configured.monthlyReceiptLimit(free));
        assertEquals(3, configured.watchedMarketLimit(free));
        configured.require(free, Feature.PUSH_AND_EMAIL_DELIVERY); // no exception
        assertThrows(PaywallException.class, () -> configured.require(free, Feature.PHOTO_EXTRACTION));
    }

    @Test
    void requireWithinLimit_freeIsAllowedUpToTheCapThenBlocked() {
        var free = user(SubscriptionTier.FREE);

        gate.requireWithinLimit(free, Feature.WATCHED_MARKETS_UNLIMITED, 2); // third pin is fine
        assertThrows(PaywallException.class,
                () -> gate.requireWithinLimit(free, Feature.WATCHED_MARKETS_UNLIMITED, 3));
        gate.requireWithinLimit(free, Feature.RECEIPT_UPLOAD_UNLIMITED, 4);
        var blocked = assertThrows(PaywallException.class,
                () -> gate.requireWithinLimit(free, Feature.RECEIPT_UPLOAD_UNLIMITED, 5));
        assertEquals("RECEIPT_UPLOAD_UNLIMITED", blocked.getFeatureName());
    }

    @Test
    void requireWithinLimit_neverBlocksProOrDormantGate() {
        gate.requireWithinLimit(user(SubscriptionTier.PRO), Feature.RECEIPT_UPLOAD_UNLIMITED, 10_000);
        new SubscriptionGateService(new CollaborativeProperties())
                .requireWithinLimit(user(SubscriptionTier.FREE), Feature.WATCHED_MARKETS_UNLIMITED, 10_000);
    }

    @Test
    void requireWithinLimit_rejectsAFeatureThatIsNotMetered() {
        var free = user(SubscriptionTier.FREE);
        assertThrows(IllegalArgumentException.class,
                () -> gate.requireWithinLimit(free, Feature.CSV_EXPORT, 0));
    }

    // ---------- notification delivery ----------

    @Test
    void allowsDelivery_transactionalNotificationsAreNeverBlockedForFree() {
        var free = user(SubscriptionTier.FREE);
        var transactional = List.of(
                NotificationType.RECEIPT_READY, NotificationType.RECEIPT_FAILED, NotificationType.RECEIPT_RECOVERED,
                NotificationType.HOUSEHOLD_MEMBER_JOINED, NotificationType.HOUSEHOLD_MEMBER_LEFT);

        for (var type : transactional) {
            assertTrue(gate.allowsDelivery(free, type, false), type + " must always be delivered");
        }
        // Organic SYSTEM notices (account, billing, consent) are transactional too…
        assertTrue(gate.allowsDelivery(free, NotificationType.SYSTEM, false));
        // …but a SYSTEM notification sent by an admin campaign is marketing.
        assertFalse(gate.allowsDelivery(free, NotificationType.SYSTEM, true));
    }

    @Test
    void allowsDelivery_promotionalTypesAreProOnlyWhenEnforced() {
        var free = user(SubscriptionTier.FREE);
        var promotional = List.of(NotificationType.PROMO_PERSONAL, NotificationType.PRICE_DROP,
                NotificationType.DEALS_DIGEST, NotificationType.STOCKOUT, NotificationType.BUDGET);

        for (var type : promotional) {
            assertFalse(gate.allowsDelivery(free, type, false), type + " is PRO delivery");
            assertTrue(gate.allowsDelivery(user(SubscriptionTier.PRO), type, false));
        }
    }

    @Test
    void allowsDelivery_enforcementOffDeliversEverything() {
        var dormant = new SubscriptionGateService(new CollaborativeProperties());
        var free = user(SubscriptionTier.FREE);

        for (var type : NotificationType.values()) {
            assertTrue(dormant.allowsDelivery(free, type, true));
        }
    }

    // ---------- household member cap ----------

    @Test
    void householdMemberCap_enforced_allFree_isFreeCapAndUpgradeable() {
        var cap = gate.householdMemberCap(List.of(user(SubscriptionTier.FREE), user(SubscriptionTier.FREE)));

        assertEquals(2, cap.limit());
        assertTrue(cap.upgradeAvailable());
    }

    @Test
    void householdMemberCap_enforced_anyProLiftsToMax() {
        var cap = gate.householdMemberCap(List.of(user(SubscriptionTier.FREE), user(SubscriptionTier.PRO)));

        assertEquals(6, cap.limit());
        assertFalse(cap.upgradeAvailable(), "already at the ceiling — nothing to buy");
    }

    @Test
    void householdMemberCap_enforcementOff_isAbsoluteMaxForEveryone() {
        var dormant = new SubscriptionGateService(new CollaborativeProperties());

        var cap = dormant.householdMemberCap(List.of(user(SubscriptionTier.FREE)));

        assertEquals(6, cap.limit());
        assertFalse(cap.upgradeAvailable());
    }

    @Test
    void householdMemberCap_readsConfiguredLimits() {
        var properties = new CollaborativeProperties();
        properties.getSubscription().setEnforce(true);
        properties.getSubscription().setHouseholdMembersMax(10);
        properties.getSubscription().getFree().setHouseholdMembers(3);
        var configured = new SubscriptionGateService(properties);

        assertEquals(3, configured.householdMemberCap(List.of(user(SubscriptionTier.FREE))).limit());
        assertEquals(10, configured.householdMemberCap(List.of(user(SubscriptionTier.PRO))).limit());
    }
}
