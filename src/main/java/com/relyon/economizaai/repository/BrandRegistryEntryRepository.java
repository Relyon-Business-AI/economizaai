package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.BrandRegistryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandRegistryEntryRepository extends JpaRepository<BrandRegistryEntry, UUID> {

    Optional<BrandRegistryEntry> findByNormalizedKey(String normalizedKey);

    /**
     * Distinct display names whose normalized key contains {@code query} (already
     * normalized by the caller). Blank query returns the first page alphabetically.
     * Powers the brand autocomplete in the admin rule editor.
     */
    @Query("""
        SELECT DISTINCT b.displayName FROM BrandRegistryEntry b
        WHERE :query = '' OR b.normalizedKey LIKE CONCAT('%', :query, '%')
        ORDER BY b.displayName
    """)
    List<String> searchDisplayNames(@Param("query") String query, Pageable pageable);

    /** Full rows (id + key + source) for the admin management list, filtered like the autocomplete. */
    @Query("""
        SELECT b FROM BrandRegistryEntry b
        WHERE :query = '' OR b.normalizedKey LIKE CONCAT('%', :query, '%')
        ORDER BY b.normalizedKey
    """)
    List<BrandRegistryEntry> searchEntries(@Param("query") String query, Pageable pageable);
}
