package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiFindingRepository extends JpaRepository<AiFinding, UUID> {

    Page<AiFinding> findByStatusOrderByCreatedAtDesc(AiFindingStatus status, Pageable pageable);

    Page<AiFinding> findByStatusAndTypeOrderByCreatedAtDesc(AiFindingStatus status, AiFindingType type, Pageable pageable);

    long countByStatus(AiFindingStatus status);

    // Dedup guard: don't re-propose something identical that's still pending or was already reviewed.
    boolean existsByTypeAndTitleAndStatus(AiFindingType type, String title, AiFindingStatus status);

    boolean existsByTypeAndTitle(AiFindingType type, String title);
}
