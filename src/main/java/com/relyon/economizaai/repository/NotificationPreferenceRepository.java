package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByUserIdAndType(UUID userId, NotificationType type);

    List<NotificationPreference> findAllByUserId(UUID userId);
}
