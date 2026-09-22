package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CategorizationBenchmarkEntryRepository extends JpaRepository<CategorizationBenchmarkEntry, UUID> {

    Optional<CategorizationBenchmarkEntry> findByDescription(String description);
}
