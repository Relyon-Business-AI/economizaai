package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByUserId(UUID userId);

    /**
     * ACTIVE subscriptions whose paid period has elapsed — candidates to expire.
     * Open-ended grants (null currentPeriodEnd, e.g. admin promos) never lapse.
     * Fetches the user so the caller can downgrade the tier without an N+1.
     */
    @Query("""
        SELECT s FROM Subscription s
        JOIN FETCH s.user
        WHERE s.status = :status
          AND s.currentPeriodEnd IS NOT NULL
          AND s.currentPeriodEnd < :cutoff
    """)
    List<Subscription> findActiveExpiredBefore(@Param("status") SubscriptionStatus status,
                                               @Param("cutoff") LocalDateTime cutoff);

    // --- Subscription analytics (admin dashboard) ---
    // Promo/admin grants are recorded with provider "manual" (SubscriptionService.grantSignupPromoIfEnabled)
    // or null; a genuinely paying subscription carries a real payment-provider name (stripe/mercadopago/...).

    // `:includeInternal = false` excludes admins and test accounts (@economizaai.app), matching the acquisition view.
    String SUB_INTERNAL_FILTER =
            " AND (:includeInternal = TRUE OR (s.user.role <> 'ADMIN' "
            + "AND s.user.excludedFromMetrics = FALSE "
            + "AND lower(s.user.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(s.user.email) NOT LIKE '%@cloudtestlabaccounts.com'))";

    /** ACTIVE subscriptions backed by a real payment provider — genuinely paying, NOT promo/manual grants. */
    @Query("SELECT count(s) FROM Subscription s WHERE s.status = :status "
            + "AND s.provider IS NOT NULL AND s.provider <> 'manual'" + SUB_INTERNAL_FILTER)
    long countPaying(@Param("status") SubscriptionStatus status, boolean includeInternal);

    /** ACTIVE subscriptions that are promo / admin manual grants ("até segunda ordem") — provider null or "manual". */
    @Query("SELECT count(s) FROM Subscription s WHERE s.status = :status "
            + "AND (s.provider IS NULL OR s.provider = 'manual')" + SUB_INTERNAL_FILTER)
    long countPromoGranted(@Param("status") SubscriptionStatus status, boolean includeInternal);
}
