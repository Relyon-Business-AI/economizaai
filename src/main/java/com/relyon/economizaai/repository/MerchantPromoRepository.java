package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MerchantPromo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantPromoRepository extends JpaRepository<MerchantPromo, UUID> {

    Page<MerchantPromo> findAllByCnpjRootInOrderByEndsAtDesc(List<String> cnpjRoots, Pageable pageable);

    Optional<MerchantPromo> findByIdAndCnpjRootIn(UUID id, List<String> cnpjRoots);

    Page<MerchantPromo> findAllByCnpjRootOrderByEndsAtDesc(String cnpjRoot, Pageable pageable);

    /** Live sponsored promos for the community feed (flag-gated at the service). */
    @Query("""
        SELECT promo FROM MerchantPromo promo
        WHERE promo.active = true
          AND promo.startsAt <= :day
          AND promo.endsAt >= :day
        ORDER BY promo.endsAt ASC
    """)
    List<MerchantPromo> findLiveOn(@Param("day") LocalDate day);

    /** Duplicate guard: an overlapping active promo for the same (chain, EAN). */
    @Query("""
        SELECT COUNT(promo) > 0 FROM MerchantPromo promo
        WHERE promo.cnpjRoot = :cnpjRoot
          AND promo.ean = :ean
          AND promo.active = true
          AND promo.startsAt <= :endsAt
          AND promo.endsAt >= :startsAt
          AND (:excludeId IS NULL OR promo.id <> :excludeId)
    """)
    boolean existsOverlapping(@Param("cnpjRoot") String cnpjRoot, @Param("ean") String ean,
                              @Param("startsAt") LocalDate startsAt, @Param("endsAt") LocalDate endsAt,
                              @Param("excludeId") UUID excludeId);
}
