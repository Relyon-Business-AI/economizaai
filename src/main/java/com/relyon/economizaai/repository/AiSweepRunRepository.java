package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AiSweepRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiSweepRunRepository extends JpaRepository<AiSweepRun, UUID> {

    Optional<AiSweepRun> findTopByOrderByCreatedAtDesc();

    boolean existsByStatus(String status);
}
