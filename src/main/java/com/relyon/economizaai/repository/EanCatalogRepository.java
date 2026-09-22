package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.EanCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EanCatalogRepository extends JpaRepository<EanCatalogEntry, UUID> {

    Optional<EanCatalogEntry> findByEan(String ean);

    List<EanCatalogEntry> findByEanIn(Collection<String> eans);

    // Brand derivation: distinct raw brand strings with their catalog frequency,
    // so the registry can be grown from data we already imported (Open Food Facts).
    @Query("select e.brand as brand, count(e) as occurrences from EanCatalogEntry e " +
           "where e.brand is not null and e.brand <> '' group by e.brand")
    List<BrandOccurrence> countByBrand();

    interface BrandOccurrence {
        String getBrand();
        long getOccurrences();
    }
}
