package com.relyon.economizaai.service;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import com.relyon.economizaai.service.geo.HomeLocationPrecision;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Settings that are stored under a member but belong to the household: the home location
 * (one point for everyone), the watched markets and the price alerts.
 *
 * <p>The home location is kept identical on every member, so every read of a user's own
 * point is already the household's. Watched markets and price alerts stay under the member
 * who created them and are read household-wide; a member who leaves takes a copy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdSharedSettingsService {

    private static final Comparator<User> BY_HOME_SET_AT =
            Comparator.comparing(User::getHomeSetAt, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final UserRepository userRepository;
    private final UserWatchedMarketRepository watchedMarketRepository;
    private final NotificationRuleRepository ruleRepository;

    /** Saves the point as the home of every member of the user's household; returns the saved user. */
    @Transactional
    public User saveHomeLocationForHousehold(User user, BigDecimal latitude, BigDecimal longitude) {
        var members = userRepository.findAllByHouseholdId(user.getHousehold().getId());
        var savedAt = LocalDateTime.now();
        var coarseLatitude = HomeLocationPrecision.coarsen(latitude);
        var coarseLongitude = HomeLocationPrecision.coarsen(longitude);
        members.forEach(member -> applyHomeLocation(member, coarseLatitude, coarseLongitude, savedAt));
        userRepository.saveAll(members);
        log.info("home_location.set user={} members={}", LogMasker.email(user.getEmail()), members.size());
        return members.stream()
                .filter(member -> member.getId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Gives every member the household's most recently saved home point — run when the
     * membership changes (someone joined), so the newcomer and the household end up with one.
     */
    @Transactional
    public void shareMostRecentHomeLocation(UUID householdId) {
        var members = userRepository.findAllByHouseholdId(householdId);
        mostRecentHome(members).ifPresent(mostRecent -> {
            members.forEach(member -> applyHomeLocation(member,
                    mostRecent.getHomeLatitude(), mostRecent.getHomeLongitude(), mostRecent.getHomeSetAt()));
            userRepository.saveAll(members);
            log.info("home_location.shared household={} members={}", householdId, members.size());
        });
    }

    /**
     * Before {@code leavingMember} moves out of {@code sharedHouseholdId}: copies the household's
     * watched markets and price alerts that other members created, so they leave with them.
     */
    @Transactional
    public void copySharedSettingsToLeavingMember(User leavingMember, UUID sharedHouseholdId) {
        var copiedMarkets = copyWatchedMarkets(leavingMember, sharedHouseholdId);
        var copiedAlerts = copyPriceAlerts(leavingMember, sharedHouseholdId);
        log.info("household.shared_settings_copied user={} markets={} alerts={}",
                LogMasker.email(leavingMember.getEmail()), copiedMarkets, copiedAlerts);
    }

    private Optional<User> mostRecentHome(List<User> members) {
        return members.stream()
                .filter(member -> member.getHomeLatitude() != null && member.getHomeLongitude() != null)
                .max(BY_HOME_SET_AT);
    }

    private void applyHomeLocation(User member, BigDecimal latitude, BigDecimal longitude, LocalDateTime setAt) {
        member.setHomeLatitude(latitude);
        member.setHomeLongitude(longitude);
        member.setHomeSetAt(setAt);
    }

    private int copyWatchedMarkets(User leavingMember, UUID sharedHouseholdId) {
        var householdPins = watchedMarketRepository.findAllByUserHouseholdId(sharedHouseholdId);
        var ownCnpjs = householdPins.stream()
                .filter(pin -> pin.getUser().getId().equals(leavingMember.getId()))
                .map(UserWatchedMarket::getMarketCnpj)
                .collect(Collectors.toSet());
        var copies = householdPins.stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .distinct()
                .filter(cnpj -> !ownCnpjs.contains(cnpj))
                .map(cnpj -> UserWatchedMarket.builder().user(leavingMember).marketCnpj(cnpj).build())
                .toList();
        watchedMarketRepository.saveAll(copies);
        return copies.size();
    }

    private int copyPriceAlerts(User leavingMember, UUID sharedHouseholdId) {
        var householdAlerts = ruleRepository.findAllByHouseholdIdAndTypeFetchProduct(
                sharedHouseholdId, NotificationType.PRICE_DROP);
        var coveredProductIds = householdAlerts.stream()
                .filter(alert -> alert.getUser().getId().equals(leavingMember.getId()))
                .map(alert -> alert.getProduct().getId())
                .collect(Collectors.toCollection(HashSet::new));
        var copies = householdAlerts.stream()
                .filter(alert -> coveredProductIds.add(alert.getProduct().getId()))
                .map(alert -> copyOf(alert, leavingMember))
                .toList();
        ruleRepository.saveAll(copies);
        return copies.size();
    }

    private NotificationRule copyOf(NotificationRule alert, User newOwner) {
        return NotificationRule.builder()
                .user(newOwner)
                .type(alert.getType())
                .product(alert.getProduct())
                .thresholdPrice(alert.getThresholdPrice())
                .radiusKm(alert.getRadiusKm())
                .channel(alert.getChannel())
                .active(alert.isActive())
                .isDefault(false)
                .build();
    }
}
