package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * Products the strong (free) layers left wanting: unmatched/weak category
     * source, category OTHER, or missing brand/pack — the LLM teacher queue.
     * Attempt-capped so hopeless rows stop consuming paid calls.
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.llmEnrichmentAttempts < :maxAttempts
          AND p.llmEnrichedAt IS NULL
          AND (p.categorizationSource IN ('NONE', 'ML', 'LEARNED_DICTIONARY')
               OR p.category = 'OTHER'
               OR p.brand IS NULL
               OR p.packSize IS NULL)
        ORDER BY p.createdAt DESC
    """)
    List<Product> findEnrichmentCandidates(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    @Query("""
        SELECT COUNT(p) FROM Product p
        WHERE p.llmEnrichmentAttempts < :maxAttempts
          AND p.llmEnrichedAt IS NULL
          AND (p.categorizationSource IN ('NONE', 'ML', 'LEARNED_DICTIONARY')
               OR p.category = 'OTHER'
               OR p.brand IS NULL
               OR p.packSize IS NULL)
    """)
    long countEnrichmentCandidates(@Param("maxAttempts") int maxAttempts);

    /**
     * Nightly-audit sample: machine-labeled products (human truth excluded),
     * volume-weighted so a wrong label on a daily staple outranks a one-off,
     * and not re-audited within the cooldown.
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.categorizationSource IN ('LLM', 'LEARNED_DICTIONARY', 'ML', 'DICTIONARY')
          AND (p.llmAuditedAt IS NULL OR p.llmAuditedAt < :auditedBefore)
        ORDER BY (SELECT COUNT(ri) FROM ReceiptItem ri WHERE ri.product = p) DESC
    """)
    List<Product> findAuditSample(@Param("auditedBefore") LocalDateTime auditedBefore, Pageable pageable);

    long countByCategorizationSource(CategorizationSource categorizationSource);



    Optional<Product> findByEan(String ean);

    List<Product> findByCategorizationSourceIn(Collection<CategorizationSource> sources);

    /** Products that have any category assigned — for catalog coverage metrics. */
    long countByCategoryNotNull();

    /**
     * Distinct OTHER-category products that have at least one confirmed,
     * non-excluded purchase at the given merchant CNPJ. Used to backfill the
     * HEALTH category onto products that were canonicalized before their
     * merchant was verified as a pharmacy. USER/MERCHANT-locked products are excluded.
     */
    @Query("""
        SELECT DISTINCT ri.product FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.cnpjEmitente = :cnpj
          AND ri.excluded = false
          AND ri.product.category = 'OTHER'
          AND ri.product.categorizationSource <> 'USER'
          AND ri.product.categorizationSource <> 'MERCHANT'
    """)
    List<Product> findOtherCategoryProductsByMerchant(@Param("cnpj") String cnpj);

    @Query("""
        SELECT p FROM Product p
        WHERE (:query IS NULL OR LOWER(p.normalizedName) LIKE LOWER(CONCAT('%', :query, '%')) OR p.ean = :query)
        ORDER BY p.normalizedName ASC
    """)
    Page<Product> search(@Param("query") String query, Pageable pageable);

    /** Admin catalog list with optional filters — any null param is ignored (matches all). */
    @Query("""
        SELECT p FROM Product p
        WHERE (:query IS NULL OR LOWER(p.normalizedName) LIKE LOWER(CONCAT('%', :query, '%')) OR p.ean = :query)
          AND (:category IS NULL OR p.category = :category)
          AND (:source IS NULL OR p.categorizationSource = :source)
        ORDER BY p.normalizedName ASC
    """)
    Page<Product> findFiltered(@Param("query") String query,
                               @Param("category") ProductCategory category,
                               @Param("source") CategorizationSource source,
                               Pageable pageable);

    @Query("""
        SELECT p FROM Product p
        WHERE (:query IS NULL OR LOWER(p.normalizedName) LIKE LOWER(CONCAT('%', :query, '%')) OR p.ean = :query)
        ORDER BY p.normalizedName ASC
    """)
    List<Product> searchAll(@Param("query") String query);

    /**
     * Products matching all four metadata dimensions. Used by
     * {@code CanonicalizationService} to dedup against an existing product
     * when an unknown EAN comes in (small markets sometimes use internal
     * pseudo-EANs for the same physical product). Caller should require
     * all four arguments to be non-null — otherwise the candidate pool is
     * too loose and false-positive-prone.
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.genericNameNorm = :genericNameNorm
          AND p.brandNorm = :brandNorm
          AND p.packSize = :packSize
          AND p.packUnit = :packUnit
    """)
    List<Product> findByMetadata(@Param("genericNameNorm") String genericNameNorm,
                                 @Param("brandNorm") String brandNorm,
                                 @Param("packSize") BigDecimal packSize,
                                 @Param("packUnit") String packUnit);

    /**
     * Products without a brand — used by the admin curation endpoint
     * (Item C.2). Sorted by normalizedName so the same product surfaces
     * in the same place across pages.
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.brand IS NULL OR p.brand = ''
        ORDER BY p.normalizedName ASC
    """)
    Page<Product> findMissingBrand(Pageable pageable);

    /**
     * All products that share a `(genericName, brand, packSize, packUnit)`
     * tuple with at least one other product — i.e. probable duplicates that
     * the admin merge tool should surface. Sorted so duplicates of the same
     * profile come back contiguously and the service layer can group them.
     * Only considers products with all four metadata dimensions populated;
     * items missing any dimension can't be reliably deduped this way (and
     * are already filtered out from the runtime metadata-dedup gate).
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.genericNameNorm IS NOT NULL
          AND p.brandNorm IS NOT NULL
          AND p.packSize IS NOT NULL
          AND p.packUnit IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM Product p2
              WHERE p2.id <> p.id
                AND p2.genericNameNorm = p.genericNameNorm
                AND p2.brandNorm = p.brandNorm
                AND p2.packSize = p.packSize
                AND p2.packUnit = p.packUnit
          )
        ORDER BY p.genericNameNorm ASC, p.brandNorm ASC, p.packSize ASC, p.packUnit ASC, p.createdAt ASC
    """)
    List<Product> findDuplicateCandidates();

    /** Products whose normalized mirror columns still need populating (one-time backfill; empty after). */
    @Query("""
        SELECT p FROM Product p
        WHERE (p.genericName IS NOT NULL AND p.genericNameNorm IS NULL)
           OR (p.brand IS NOT NULL AND p.brandNorm IS NULL)
    """)
    List<Product> findNeedingNormBackfill();
}
