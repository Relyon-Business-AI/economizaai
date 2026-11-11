package com.relyon.economizaai.service.home;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.HomeAvailabilityResponse;
import com.relyon.economizaai.dto.response.HomeAvailabilityResponse.FeatureAvailability;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AvailabilityReason;
import com.relyon.economizaai.model.enums.HomeFeature;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Computes the home-screen cold-start availability map: for each volume-gated
 * feature, whether the account has crossed the threshold where it starts
 * delivering value, so the FE can lock/blur the rest with a progress hint instead
 * of showing a confusing empty screen.
 *
 * <p>Cheap by design — two count queries (household confirmed receipts + distinct
 * contributing households), no per-feature detection re-run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeAvailabilityService {

    private final ReceiptRepository receiptRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;
    private final CollaborativeProperties properties;

    private static final Duration CONTRIBUTING_HOUSEHOLDS_TTL = Duration.ofMinutes(10);
    private volatile ContributingHouseholds contributingHouseholdsCache;

    public HomeAvailabilityResponse forUser(User user) {
        var confirmedReceipts = (int) receiptRepository.countByHouseholdIdAndStatus(
                user.getHousehold().getId(), ReceiptStatus.CONFIRMED);
        var contributingHouseholds = cachedContributingHouseholds();

        var communityNeed = properties.getCollaborative().getMinHouseholdsForPublic();
        var features = List.of(
                personal(HomeFeature.CONSUMPTION_PREDICTIONS, confirmedReceipts,
                        properties.getConsumption().getFallbackMinPurchasesForPrediction()),
                personal(HomeFeature.SUGGESTED_LIST, confirmedReceipts,
                        properties.getConsumption().getFallbackMinPurchasesForPrediction()),
                personal(HomeFeature.PERSONAL_PROMOS, confirmedReceipts,
                        properties.getPersonalPromo().getMinPurchasesForBaseline()),
                personal(HomeFeature.PREFERENCES, confirmedReceipts,
                        properties.getPreferences().getMinPurchasesPerGeneric()),
                community(HomeFeature.COMMUNITY_DEALS, contributingHouseholds, communityNeed),
                community(HomeFeature.COMMUNITY_PROMOS, contributingHouseholds, communityNeed),
                community(HomeFeature.BEST_MARKETS, contributingHouseholds, communityNeed),
                community(HomeFeature.REFERENCE_PRICE, contributingHouseholds, communityNeed));

        var locked = features.stream().filter(feature -> !feature.available()).count();
        log.debug("home.availability user={} receipts={} households={} locked={}/{}",
                abbrev(user), confirmedReceipts, contributingHouseholds, locked, features.size());
        return new HomeAvailabilityResponse(features);
    }

    /**
     * The contributor count is a GLOBAL scan (audits ⨝ observations) and this endpoint
     * runs on every app open — the number only matters against a small threshold, so a
     * few minutes of staleness is harmless.
     */
    private int cachedContributingHouseholds() {
        var cached = contributingHouseholdsCache;
        if (cached != null && cached.countedAt().plus(CONTRIBUTING_HOUSEHOLDS_TTL).isAfter(Instant.now())) {
            return cached.households();
        }
        var counted = (int) priceObservationAuditRepository.countDistinctContributingHouseholds();
        contributingHouseholdsCache = new ContributingHouseholds(counted, Instant.now());
        return counted;
    }

    private record ContributingHouseholds(int households, Instant countedAt) {}

    private FeatureAvailability personal(HomeFeature feature, int have, int need) {
        var available = have >= need;
        return new FeatureAvailability(feature, available,
                available ? AvailabilityReason.AVAILABLE : AvailabilityReason.NEEDS_MORE_RECEIPTS, have, need);
    }

    private FeatureAvailability community(HomeFeature feature, int have, int need) {
        var available = have >= need;
        return new FeatureAvailability(feature, available,
                available ? AvailabilityReason.AVAILABLE : AvailabilityReason.NEEDS_COMMUNITY, have, need);
    }

    private static String abbrev(User user) {
        return user.getId() == null ? "" : user.getId().toString().substring(0, 8);
    }
}
