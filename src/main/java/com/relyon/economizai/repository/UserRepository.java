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
    // `:includeInternal = false` (the dashboard default) excludes admins and test
    // accounts (@economizaai.app) so the numbers reflect real users only. The
    // filter clause is repeated per query because JPQL has no shared predicate.
    String INTERNAL_FILTER =
            " AND (:includeInternal = TRUE OR (user.role <> 'ADMIN' "
            + "AND user.excludedFromMetrics = FALSE "
            + "AND lower(user.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(user.email) NOT LIKE '%@cloudtestlabaccounts.com'))";

    /** (createdAt, acquisitionChannel) for every signup since the window start; bucketed into a daily series in the service. */
    @Query("SELECT user.createdAt, user.acquisitionChannel FROM User user "
            + "WHERE user.createdAt >= :since" + INTERNAL_FILTER + " ORDER BY user.createdAt")
    List<Object[]> signupTimelineSince(LocalDateTime since, boolean includeInternal);

    /** Per-channel counts in the window: (channel, signups, verified, proTier). */
    @Query("SELECT user.acquisitionChannel, count(user), "
            + "sum(case when user.emailVerified = true then 1 else 0 end), "
            + "sum(case when user.subscriptionTier = 'PRO' then 1 else 0 end) "
            + "FROM User user WHERE user.createdAt >= :since" + INTERNAL_FILTER
            + " GROUP BY user.acquisitionChannel")
    List<Object[]> channelBreakdownSince(LocalDateTime since, boolean includeInternal);

    /** Per-registration-platform counts in the window: (platform, signups) — breaks down the "unknown" channel. */
    @Query("SELECT user.registrationPlatform, count(user) "
            + "FROM User user WHERE user.createdAt >= :since" + INTERNAL_FILTER
            + " GROUP BY user.registrationPlatform")
    List<Object[]> platformBreakdownSince(LocalDateTime since, boolean includeInternal);

    /** Per-campaign counts in the window: (source, medium, campaign, signups, verified, proTier, channel). */
    @Query("SELECT user.utmSource, user.utmMedium, user.utmCampaign, count(user), "
            + "sum(case when user.emailVerified = true then 1 else 0 end), "
            + "sum(case when user.subscriptionTier = 'PRO' then 1 else 0 end), user.acquisitionChannel "
            + "FROM User user WHERE user.createdAt >= :since" + INTERNAL_FILTER
            + " GROUP BY user.utmSource, user.utmMedium, user.utmCampaign, user.acquisitionChannel")
    List<Object[]> campaignBreakdownSince(LocalDateTime since, boolean includeInternal);

    /** Lifetime tier split: (subscriptionTier, count). */
    @Query("SELECT user.subscriptionTier, count(user) FROM User user "
            + "WHERE (:includeInternal = TRUE OR (user.role <> 'ADMIN' "
            + "AND user.excludedFromMetrics = FALSE "
            + "AND lower(user.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(user.email) NOT LIKE '%@cloudtestlabaccounts.com')) GROUP BY user.subscriptionTier")
    List<Object[]> tierDistribution(boolean includeInternal);

    @Query("SELECT count(user) FROM User user WHERE user.createdAt >= :since" + INTERNAL_FILTER)
    long countSignupsSince(LocalDateTime since, boolean includeInternal);

    @Query("SELECT count(user) FROM User user WHERE user.createdAt >= :since "
            + "AND user.emailVerified = true" + INTERNAL_FILTER)
    long countVerifiedSince(LocalDateTime since, boolean includeInternal);

    /** Signups in the window who have uploaded at least one receipt ("activated"). */
    @Query("SELECT count(distinct user) FROM User user WHERE user.createdAt >= :since "
            + "AND exists (select 1 from Receipt receipt where receipt.user = user)" + INTERNAL_FILTER)
    long countActivatedSince(LocalDateTime since, boolean includeInternal);

    @Query("SELECT count(user) FROM User user WHERE user.createdAt >= :since "
            + "AND user.subscriptionTier = 'PRO'" + INTERNAL_FILTER)
    long countProTierSince(LocalDateTime since, boolean includeInternal);

    /** Lifetime PRO count (all active PRO tiers) — the base for the MRR proxy. */
    @Query("SELECT count(user) FROM User user WHERE user.subscriptionTier = 'PRO'" + INTERNAL_FILTER)
    long countProTier(boolean includeInternal);

    /**
     * One row per signup in the window: (acquisitionChannel, createdAt, firstReceiptAt).
     * firstReceiptAt is null when the user never activated. The service buckets these
     * into per-channel activation + D7/D30 retention cohorts (cheap: bounded by window signups).
     */
    @Query("SELECT user.acquisitionChannel, user.createdAt, "
            + "(SELECT min(receipt.createdAt) FROM Receipt receipt WHERE receipt.user = user) "
            + "FROM User user WHERE user.createdAt >= :since" + INTERNAL_FILTER)
    List<Object[]> signupActivationSince(LocalDateTime since, boolean includeInternal);

    /** Same exclusion as {@link #INTERNAL_FILTER} but for native SQL (alias {@code u}, column names). */
    String NATIVE_INTERNAL_FILTER =
            " AND (:includeInternal = TRUE OR (u.role <> 'ADMIN' "
            + "AND u.excluded_from_metrics = FALSE "
            + "AND lower(u.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(u.email) NOT LIKE '%@cloudtestlabaccounts.com'))";

    /**
     * Weekly cohort sizes: (cohortWeekStart, acquisitionChannel, size) — signups bucketed
     * by their ISO week (Monday) since the window start. The service pairs this with
     * {@link #cohortActivitySince} to build the retention triangle.
     */
    @Query(value = "SELECT date_trunc('week', u.created_at)::date AS cohort_week, "
            + "u.acquisition_channel AS channel, count(*) AS cohort_size "
            + "FROM users u WHERE u.created_at >= :since" + NATIVE_INTERNAL_FILTER
            + " GROUP BY cohort_week, channel", nativeQuery = true)
    List<Object[]> cohortSizesSince(LocalDateTime since, boolean includeInternal);

    /**
     * Weekly cohort activity grid: (cohortWeekStart, acquisitionChannel, weekOffset,
     * distinctActiveUsers). {@code weekOffset} is whole weeks between the signup week and
     * the receipt week (0 = signup week). Counts are distinct users who scanned at least
     * one receipt in that offset week.
     */
    @Query(value = "SELECT date_trunc('week', u.created_at)::date AS cohort_week, "
            + "u.acquisition_channel AS channel, "
            + "(date_trunc('week', r.created_at)::date - date_trunc('week', u.created_at)::date) / 7 AS week_offset, "
            + "count(DISTINCT u.id) AS active_users "
            + "FROM users u JOIN receipts r ON r.user_id = u.id "
            + "WHERE u.created_at >= :since" + NATIVE_INTERNAL_FILTER
            + " GROUP BY cohort_week, channel, week_offset", nativeQuery = true)
    List<Object[]> cohortActivitySince(LocalDateTime since, boolean includeInternal);
}
