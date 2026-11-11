package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PremiumGrant;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PremiumGrantKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PremiumGrantRepository extends JpaRepository<PremiumGrant, UUID> {

    boolean existsByUserIdAndKind(UUID userId, PremiumGrantKind kind);

    /** An invited person yields at most one reward for their inviter, whatever the kind. */
    boolean existsByUserIdAndSourceUserId(UUID userId, UUID sourceUserId);

    long countByUserId(UUID userId);

    /** True while one of the user's grant windows is running. */
    @Query("""
        SELECT COUNT(premiumGrant) > 0 FROM PremiumGrant premiumGrant
        WHERE premiumGrant.user.id = :userId AND premiumGrant.startsAt <= :now AND premiumGrant.endsAt > :now
    """)
    boolean existsActive(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** End of the user's last grant window (null when they have none) — where the next reward queues. */
    @Query("SELECT MAX(premiumGrant.endsAt) FROM PremiumGrant premiumGrant WHERE premiumGrant.user.id = :userId")
    LocalDateTime findLatestEndByUserId(@Param("userId") UUID userId);

    /** FREE users whose grant window has started — the sweep promotes them. */
    @Query("""
        SELECT DISTINCT premiumGrant.user FROM PremiumGrant premiumGrant
        WHERE premiumGrant.user.subscriptionTier = 'FREE'
          AND premiumGrant.startsAt <= :now AND premiumGrant.endsAt > :now
    """)
    List<User> findFreeUsersWithActiveGrant(@Param("now") LocalDateTime now);

    /**
     * PRO users whose Premium came from a grant that is over: every grant window ended, none is
     * running, and no ACTIVE subscription backs the tier — the sweep downgrades them.
     */
    @Query("""
        SELECT DISTINCT premiumGrant.user FROM PremiumGrant premiumGrant
        WHERE premiumGrant.user.subscriptionTier = 'PRO'
          AND premiumGrant.endsAt <= :now
          AND NOT EXISTS (
              SELECT running.id FROM PremiumGrant running
              WHERE running.user = premiumGrant.user AND running.startsAt <= :now AND running.endsAt > :now)
          AND NOT EXISTS (
              SELECT subscription.id FROM Subscription subscription
              WHERE subscription.user = premiumGrant.user AND subscription.status = 'ACTIVE')
    """)
    List<User> findProUsersWithOnlyEndedGrants(@Param("now") LocalDateTime now);
}
