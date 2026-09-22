package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.GarimpoWatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GarimpoWatchRepository extends JpaRepository<GarimpoWatch, UUID> {

    List<GarimpoWatch> findByActiveTrue();

    Page<GarimpoWatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
