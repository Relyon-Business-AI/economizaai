package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationAudience;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationAudienceRepository extends JpaRepository<NotificationAudience, UUID> {

    boolean existsByNameIgnoreCase(String name);

    List<NotificationAudience> findAllByOrderByBuiltInDescNameAsc();
}
