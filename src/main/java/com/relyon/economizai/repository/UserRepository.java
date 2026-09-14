package com.relyon.economizai.repository;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.model.enums.DigestFrequency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByAuthProviderAndProviderSubject(AuthProvider authProvider, String providerSubject);

    boolean existsByEmail(String email);

    List<User> findAllByHouseholdId(UUID householdId);

    long countByHouseholdId(UUID householdId);

    /**
     * Active users who haven't turned the deals digest OFF, with household
     * eagerly joined (the scheduler reads it per candidate). This is the digest
     * batch's outer slice; effective-send-hour and 1/day-cap are then applied
     * per user in the scheduler.
     */
    @Query("""
        SELECT user FROM User user
        JOIN FETCH user.household
        WHERE user.active = true
          AND user.digestFrequency <> :off
    """)
    List<User> findDigestCandidates(DigestFrequency off);

    // --- Acquisition analytics (admin dashboard) ---

    /** (createdAt, acquisitionChannel) for every signup since the window start; bucketed into a daily series in the service. */
    @Query("SELECT user.createdAt, user.acquisitionChannel FROM User user "
            + "WHERE user.createdAt >= :since ORDER BY user.createdAt")
    List<Object[]> signupTimelineSince(LocalDateTime since);

    /** Per-channel counts in the window: (channel, signups, verified, proTier). */
    @Query("SELECT user.acquisitionChannel, count(user), "
            + "sum(case when user.emailVerified = true then 1 else 0 end), "
            + "sum(case when user.subscriptionTier = 'PRO' then 1 else 0 end) "
            + "FROM User user WHERE user.createdAt >= :since GROUP BY user.acquisitionChannel")
    List<Object[]> channelBreakdownSince(LocalDateTime since);

    /** Per-campaign counts in the window: (source, medium, campaign, signups, verified, proTier). */
    @Query("SELECT user.utmSource, user.utmMedium, user.utmCampaign, count(user), "
            + "sum(case when user.emailVerified = true then 1 else 0 end), "
            + "sum(case when user.subscriptionTier = 'PRO' then 1 else 0 end) "
            + "FROM User user WHERE user.createdAt >= :since "
            + "GROUP BY user.utmSource, user.utmMedium, user.utmCampaign")
    List<Object[]> campaignBreakdownSince(LocalDateTime since);

    /** Lifetime tier split: (subscriptionTier, count). */
    @Query("SELECT user.subscriptionTier, count(user) FROM User user GROUP BY user.subscriptionTier")
    List<Object[]> tierDistribution();

    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    @Query("SELECT count(user) FROM User user WHERE user.createdAt >= :since AND user.emailVerified = true")
    long countVerifiedSince(LocalDateTime since);

    /** Signups in the window who have uploaded at least one receipt ("activated"). */
    @Query("SELECT count(distinct user) FROM User user WHERE user.createdAt >= :since "
            + "AND exists (select 1 from Receipt receipt where receipt.user = user)")
    long countActivatedSince(LocalDateTime since);

    @Query("SELECT count(user) FROM User user WHERE user.createdAt >= :since AND user.subscriptionTier = 'PRO'")
    long countProTierSince(LocalDateTime since);
}
