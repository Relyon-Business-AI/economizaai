package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, UUID> {

    Optional<ProductAlias> findByNormalizedDescription(String normalizedDescription);

    boolean existsByNormalizedDescription(String normalizedDescription);

    /**
     * Aliases of products matching the given extracted metadata profile.
     * Used by {@code CanonicalizationService} as the candidate pool for
     * fuzzy alias matching — restricted to same genericName + pack size to
     * keep false-positive risk low.
     */
    @Query("""
        SELECT a FROM ProductAlias a
        JOIN a.product p
        WHERE p.genericNameNorm = :genericNameNorm
          AND p.packSize = :packSize
          AND p.packUnit = :packUnit
    """)
    List<ProductAlias> findCandidatesByProductMetadata(@Param("genericNameNorm") String genericNameNorm,
                                                       @Param("packSize") BigDecimal packSize,
                                                       @Param("packUnit") String packUnit);

    List<ProductAlias> findByProductIdIn(List<UUID> productIds);

    long countByProduct(Product product);

    @Modifying
    @Query("UPDATE ProductAlias a SET a.product = :survivor WHERE a.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);
}
