package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

    @Query("""
        SELECT r FROM NotificationRule r
        LEFT JOIN FETCH r.product
        WHERE r.user.id = :userId
        ORDER BY r.isDefault DESC, r.createdAt DESC
    """)
    List<NotificationRule> findAllByUserIdFetchProduct(@Param("userId") UUID userId);

    Optional<NotificationRule> findByIdAndUserId(UUID id, UUID userId);

    Optional<NotificationRule> findByUserIdAndTypeAndProductId(UUID userId, NotificationType type, UUID productId);

    Optional<NotificationRule> findByUserIdAndTypeAndProductIsNull(UUID userId, NotificationType type);

    List<NotificationRule> findAllByUserId(UUID userId);

    /** Active product-scoped rules of the given types matching any product — write-path price evaluation. */
    @Query("""
        SELECT r FROM NotificationRule r
        JOIN FETCH r.user u
        JOIN FETCH r.product p
        WHERE r.type IN :types AND r.active = true AND p.id IN :productIds
    """)
    List<NotificationRule> findActiveProductRules(@Param("types") Collection<NotificationType> types,
                                                  @Param("productIds") Collection<UUID> productIds);

    /** Active rules of a given type (scheduled jobs: replenishment, budget, digest). */
    @Query("""
        SELECT r FROM NotificationRule r
        JOIN FETCH r.user u
        LEFT JOIN FETCH r.product p
        WHERE r.type = :type AND r.active = true
    """)
    List<NotificationRule> findActiveByTypeFetchUserAndProduct(@Param("type") NotificationType type);

    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO notification_rules (id, user_id, type, is_default, active, created_at, updated_at)
        SELECT gen_random_uuid(), u.id, :type, true, true, now(), now()
        FROM users u
        WHERE NOT EXISTS (
            SELECT 1 FROM notification_rules nr
            WHERE nr.user_id = u.id AND nr.type = :type AND nr.product_id IS NULL
        )
    """)
    int insertMissingDefaultRules(@Param("type") String type);
}
