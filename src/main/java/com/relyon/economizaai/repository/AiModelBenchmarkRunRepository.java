package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AiModelBenchmarkRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiModelBenchmarkRunRepository extends JpaRepository<AiModelBenchmarkRun, UUID> {
    List<AiModelBenchmarkRun> findTop20ByOrderByCreatedAtDesc();
    boolean existsByStatus(String status);
}
