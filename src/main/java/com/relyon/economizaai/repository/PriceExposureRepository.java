package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PriceExposureRepository extends JpaRepository<PriceExposure, UUID> {

    /** What one screen already showed one person on one day — the dedup set for that day. */
    List<PriceExposure> findByUserIdAndSurfaceAndShownOn(UUID userId, PriceExposureSurface surface, LocalDate shownOn);
}
