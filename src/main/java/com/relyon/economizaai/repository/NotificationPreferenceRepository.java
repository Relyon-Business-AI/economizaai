package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    /** All enabled channels the user configured for this type (multi-channel). */
    List<NotificationPreference> findAllByUserIdAndType(UUID userId, NotificationType type);

    List<NotificationPreference> findAllByUserId(UUID userId);

    @Modifying
    @Transactional
    void deleteByUserIdAndType(UUID userId, NotificationType type);
}
