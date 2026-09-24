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

    // Same, restricted to Brazilian GS1 prefixes (789/790). The OFF import is
    // ~97% foreign (US store brands like Kroger/Wegmans), so deriving from the
    // full catalog would flood the registry with brands no Brazilian receipt
    // ever mentions — BR-only is the safe default.
    @Query("select e.brand as brand, count(e) as occurrences from EanCatalogEntry e " +
           "where e.brand is not null and e.brand <> '' " +
           "and (e.ean like '789%' or e.ean like '790%') group by e.brand")
    List<BrandOccurrence> countByBrandBrazilOnly();

    interface BrandOccurrence {
        String getBrand();
        long getOccurrences();
    }
}
