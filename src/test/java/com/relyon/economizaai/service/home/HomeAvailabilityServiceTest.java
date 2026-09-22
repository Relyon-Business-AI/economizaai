package com.relyon.economizaai.service.home;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.HomeAvailabilityResponse.FeatureAvailability;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AvailabilityReason;
import com.relyon.economizaai.model.enums.HomeFeature;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeAvailabilityServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationAuditRepository priceObservationAuditRepository;

    private HomeAvailabilityService service;
    private final UUID householdId = UUID.randomUUID();
    private final User user = User.builder()
            .id(UUID.randomUUID())
            .household(Household.builder().id(householdId).build())
            .build();

    @BeforeEach
    void setUp() {
        // Real defaults: prediction=2, personal baseline=3, preferences=5, k-anon households=3.
        service = new HomeAvailabilityService(receiptRepository, priceObservationAuditRepository,
                new CollaborativeProperties());
    }

    private Map<HomeFeature, FeatureAvailability> availabilityWith(long receipts, long households) {
        lenient().when(receiptRepository.countByHouseholdIdAndStatus(eq(householdId), eq(ReceiptStatus.CONFIRMED)))
                .thenReturn(receipts);
        lenient().when(priceObservationAuditRepository.countDistinctContributingHouseholds()).thenReturn(households);
        return service.forUser(user).features().stream()
                .collect(Collectors.toMap(FeatureAvailability::feature, Function.identity()));
    }

    @Test
    void brandNewUser_everythingLockedWithReasons() {
        var byFeature = availabilityWith(0, 0);

        assertEquals(8, byFeature.size());
        var predictions = byFeature.get(HomeFeature.CONSUMPTION_PREDICTIONS);
        assertFalse(predictions.available());
        assertEquals(AvailabilityReason.NEEDS_MORE_RECEIPTS, predictions.reason());
        assertEquals(0, predictions.have());
        assertEquals(2, predictions.need());

        var deals = byFeature.get(HomeFeature.COMMUNITY_DEALS);
        assertFalse(deals.available());
        assertEquals(AvailabilityReason.NEEDS_COMMUNITY, deals.reason());
        assertEquals(3, deals.need());
    }

    @Test
    void enoughReceipts_personalUnlocked_communityStillLocked() {
        var byFeature = availabilityWith(5, 0);

        assertTrue(byFeature.get(HomeFeature.CONSUMPTION_PREDICTIONS).available());
        assertTrue(byFeature.get(HomeFeature.PERSONAL_PROMOS).available());
        assertTrue(byFeature.get(HomeFeature.PREFERENCES).available());
        assertEquals(AvailabilityReason.AVAILABLE, byFeature.get(HomeFeature.SUGGESTED_LIST).reason());

        assertFalse(byFeature.get(HomeFeature.COMMUNITY_DEALS).available());
        assertFalse(byFeature.get(HomeFeature.BEST_MARKETS).available());
    }

    @Test
    void partialReceipts_gatePerThreshold() {
        // 3 receipts: predictions (need 2) + personal promos (need 3) unlock; preferences (need 5) stays locked.
        var byFeature = availabilityWith(3, 0);

        assertTrue(byFeature.get(HomeFeature.CONSUMPTION_PREDICTIONS).available());
        assertTrue(byFeature.get(HomeFeature.PERSONAL_PROMOS).available());
        assertFalse(byFeature.get(HomeFeature.PREFERENCES).available());
        assertEquals(AvailabilityReason.NEEDS_MORE_RECEIPTS, byFeature.get(HomeFeature.PREFERENCES).reason());
    }

    @Test
    void enoughHouseholds_communityUnlocked() {
        var byFeature = availabilityWith(5, 3);

        assertTrue(byFeature.get(HomeFeature.COMMUNITY_DEALS).available());
        assertTrue(byFeature.get(HomeFeature.COMMUNITY_PROMOS).available());
        assertTrue(byFeature.get(HomeFeature.BEST_MARKETS).available());
        assertTrue(byFeature.get(HomeFeature.REFERENCE_PRICE).available());
    }
}
