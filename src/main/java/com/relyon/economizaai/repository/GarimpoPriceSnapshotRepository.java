package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.GarimpoPriceSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GarimpoPriceSnapshotRepository extends JpaRepository<GarimpoPriceSnapshot, UUID> {

    Optional<GarimpoPriceSnapshot> findFirstByProviderAndExternalIdOrderByCreatedAtDesc(
            String provider, String externalId);

    Page<GarimpoPriceSnapshot> findByProviderAndExternalIdOrderByCreatedAtDesc(
            String provider, String externalId, Pageable pageable);
}
