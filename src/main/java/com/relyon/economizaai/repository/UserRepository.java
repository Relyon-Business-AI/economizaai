package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.model.enums.DigestFrequency;
import org.springframework.data.domain.Pageable;
import com.relyon.economizaai.service.auth.EmailNormalizer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    List<User> findByPushDeviceTokenIsNull();

    /**
     * One page of users that never registered a push token and were not invited to enable
     * push since {@code nudgedAfter} — the "recently invited" check is part of the query,
     * not one lookup per user. Keyset-paged by id.
     */
    @Query("""
        SELECT user FROM User user
        WHERE user.pushDeviceToken IS NULL
          AND NOT EXISTS (SELECT 1 FROM Notification nudge
                          WHERE nudge.user = user
                            AND nudge.type = 'PUSH_OPTIN'
                            AND nudge.createdAt > :nudgedAfter)
          AND user.id > :afterId
        ORDER BY user.id ASC
    """)
    List<User> findPushOptInNudgeCandidates(@Param("nudgedAfter") LocalDateTime nudgedAfter,
                                            @Param("afterId") UUID afterId,
                                            Pageable pageable);


    /**
     * Account lookup by e-mail. E-mails are stored normalized (trim + lower case), so the
     * argument is normalized here — every caller (login, reset, social, admin, webhooks)
     * matches the same account whatever casing was typed.
     */
    default Optional<User> findByEmail(String email) {
        return findByStoredEmail(EmailNormalizer.normalize(email));
    }

    default boolean existsByEmail(String email) {
        return existsByStoredEmail(EmailNormalizer.normalize(email));
    }

    /** Exact match against the stored (already normalized) value — use {@link #findByEmail}. */
    @Query("SELECT user FROM User user WHERE user.email = :email")
    Optional<User> findByStoredEmail(String email);

    @Query("SELECT count(user) > 0 FROM User user WHERE user.email = :email")
    boolean existsByStoredEmail(String email);

    Optional<User> findByAuthProviderAndProviderSubject(AuthProvider authProvider, String providerSubject);

    List<User> findAllByHouseholdId(UUID householdId);

    long countByHouseholdId(UUID householdId);

    /** (householdId, memberCount) for the given households — batch for the admin list "Domicílio N" badge. */
    @Query("SELECT user.household.id, count(user) FROM User user "
            + "WHERE user.household.id IN :householdIds GROUP BY user.household.id")
    List<Object[]> countByHouseholdIds(List<UUID> householdIds);

    /** Is this household still someone's home (a guest away who will return to it)? */
    boolean existsByHomeHouseholdId(UUID homeHouseholdId);

    /** Users who opted in to the public leaderboard — for public handles + the opted-in household set. */
    List<User> findByShareInLeaderboardTrue();

    /** Members of the given households — resolves display handles for the leaderboard's top rows. */
    List<User> findByHouseholdIdIn(Collection<UUID> householdIds);

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

    /**
     * One page of users whose deals digest is due: active, digest not OFF (WEEKLY only on
     * the weekly day), send hour already reached today and nothing sent yet today. "Reached"
     * rather than "equal", so a late or long run still delivers. Keyset-paged by id — pass
     * the last id of the previous page.
     */
    @Query("""
        SELECT user FROM User user
        JOIN FETCH user.household
        WHERE user.active = true
          AND user.digestFrequency <> 'OFF'
          AND (user.digestFrequency <> 'WEEKLY' OR :weeklyDay = TRUE)
          AND user.digestEffectiveSendHour <= :currentHour
          AND (user.lastDigestSentAt IS NULL OR user.lastDigestSentAt < :startOfToday)
          AND user.id > :afterId
        ORDER BY user.id ASC
    """)
    List<User> findDigestDuePage(@Param("currentHour") int currentHour,
                                 @Param("weeklyDay") boolean weeklyDay,
                                 @Param("startOfToday") OffsetDateTime startOfToday,
                                 @Param("afterId") UUID afterId,
                                 Pageable pageable);

    /** One page of digest recipients that still have no send hour assigned (new accounts, cleared overrides). */
    @Query("""
        SELECT user FROM User user
        JOIN FETCH user.household
        WHERE user.active = true
          AND user.digestFrequency <> 'OFF'
          AND user.digestEffectiveSendHour IS NULL
          AND user.id > :afterId
        ORDER BY user.id ASC
    """)
    List<User> findDigestUsersWithoutSendHour(@Param("afterId") UUID afterId, Pageable pageable);

    /** Targeted write — never rewrites the rest of the user row from a stale copy. */
    @Modifying
    @Transactional
    @Query("UPDATE User user SET user.digestEffectiveSendHour = :sendHour WHERE user.id = :userId")
    int updateDigestEffectiveSendHour(@Param("userId") UUID userId, @Param("sendHour") int sendHour);

    /**
     * Atomically takes today's digest slot for a user. Returns 1 for the caller that won it
     * and 0 when it was already taken, so an overlapping run or a second instance cannot
     * send twice. Called only when there is something to send.
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE User user SET user.lastDigestSentAt = :sentAt
        WHERE user.id = :userId
          AND (user.lastDigestSentAt IS NULL OR user.lastDigestSentAt < :startOfToday)
    """)
    int claimDigestSlot(@Param("userId") UUID userId,
                        @Param("sentAt") OffsetDateTime sentAt,
                        @Param("startOfToday") OffsetDateTime startOfToday);

    /** Resolves the user behind a WhatsApp opt-in deep-link token (from "CONFIRMAR-<token>"). */
    Optional<User> findByWhatsAppLinkToken(String whatsAppLinkToken);

    /** Active users who linked their WhatsApp AND left the weekly report on — the proactive send list. */
    @Query("""
        SELECT user FROM User user
        JOIN FETCH user.household
        WHERE user.active = true
          AND user.whatsAppOptIn = true
          AND user.whatsAppWeeklyReportEnabled = true
          AND user.whatsAppNumber IS NOT NULL
    """)
    List<User> findWhatsAppWeeklyReportCandidates();

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

    /** Signups whose account was created from the web landing — the only signups the web beacon can have funneled. */
    @Query("SELECT count(user) FROM User user WHERE user.createdAt >= :since "
            + "AND user.registrationPlatform = 'WEB'" + INTERNAL_FILTER)
    long countWebSignupsSince(LocalDateTime since, boolean includeInternal);

    /** Per-campaign web signups in the window: (utmCampaign, count) — matched against web visits per campaign. */
    @Query("SELECT user.utmCampaign, count(user) FROM User user WHERE user.createdAt >= :since "
            + "AND user.registrationPlatform = 'WEB'" + INTERNAL_FILTER
            + " GROUP BY user.utmCampaign")
    List<Object[]> webCampaignSignupsSince(LocalDateTime since, boolean includeInternal);

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

    /** Total users, excluding admins/test accounts when includeInternal=false (matches the funnel counts). */
    @Query("SELECT count(user) FROM User user WHERE (:includeInternal = TRUE OR (user.role <> 'ADMIN' "
            + "AND user.excludedFromMetrics = FALSE "
            + "AND lower(user.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(user.email) NOT LIKE '%@cloudtestlabaccounts.com'))")
    long countUsers(boolean includeInternal);

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
            // GROUP BY u.acquisition_channel (não o alias `channel`): com o JOIN em receipts,
            // o alias colide com receipts.channel (coluna real), que o Postgres prioriza,
            // deixando u.acquisition_channel fora do GROUP BY e quebrando a query.
            + " GROUP BY cohort_week, u.acquisition_channel, week_offset", nativeQuery = true)
    List<Object[]> cohortActivitySince(LocalDateTime since, boolean includeInternal);
}
