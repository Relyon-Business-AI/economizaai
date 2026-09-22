package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.BrandRegistryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrandRegistryEntryRepository extends JpaRepository<BrandRegistryEntry, UUID> {

    Optional<BrandRegistryEntry> findByNormalizedKey(String normalizedKey);
}
