package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Cross-user sent notifications since a cutoff — the admin "notificações enviadas" list. */
    @Query("SELECT n FROM Notification n JOIN FETCH n.user WHERE n.createdAt >= :since ORDER BY n.createdAt DESC")
    Page<Notification> findSentSince(@Param("since") LocalDateTime since, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
