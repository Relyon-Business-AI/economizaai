package com.relyon.economizai.repository;

import com.relyon.economizai.model.Subscription;
import com.relyon.economizai.model.enums.SubscriptionStatus;
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

    /** ACTIVE subscriptions backed by a real payment provider (provider set) — genuinely paying, not promo/admin grants. */
    @Query("SELECT count(s) FROM Subscription s WHERE s.status = :status AND s.provider IS NOT NULL")
    long countPaying(@Param("status") SubscriptionStatus status);

    /** ACTIVE subscriptions with no provider — promo / admin manual grants ("até segunda ordem"). */
    @Query("SELECT count(s) FROM Subscription s WHERE s.status = :status AND s.provider IS NULL")
    long countPromoGranted(@Param("status") SubscriptionStatus status);
}
