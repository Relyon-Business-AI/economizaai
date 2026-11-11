package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.LaunchPromoWarning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LaunchPromoWarningRepository extends JpaRepository<LaunchPromoWarning, UUID> {

    boolean existsByUserIdAndMilestoneDays(UUID userId, int milestoneDays);
}
