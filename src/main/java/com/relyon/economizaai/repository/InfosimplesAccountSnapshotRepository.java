package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.InfosimplesAccountSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface InfosimplesAccountSnapshotRepository extends JpaRepository<InfosimplesAccountSnapshot, UUID> {

    Optional<InfosimplesAccountSnapshot> findTopByOrderByTakenAtDesc();

    /** Last snapshot strictly before the cutoff — the month-close job's source. */
    Optional<InfosimplesAccountSnapshot> findTopByTakenAtBeforeOrderByTakenAtDesc(OffsetDateTime cutoff);
}
