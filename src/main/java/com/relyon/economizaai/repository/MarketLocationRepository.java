package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MarketChain;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketLocationRepository extends JpaRepository<MarketLocation, UUID> {

    Optional<MarketLocation> findByCnpj(String cnpj);

    List<MarketLocation> findAllByCnpjIn(List<String> cnpjs);

    /** Every known store of a chain (merchant panel — stores appear as receipts are scanned). */
    List<MarketLocation> findAllByCnpjRoot(String cnpjRoot);

    /** Geocoded markets in a state — the sample for the UF-centroid fallback origin. */
    List<MarketLocation> findAllByStateAndLatitudeIsNotNull(String state);

    /** Markets we know about but haven't successfully geocoded yet (for the
     *  scheduled geocoding job). */
    List<MarketLocation> findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(int maxAttempts, Pageable limit);

    /** Markets whose business segment isn't resolved yet (for the scheduled
     *  CNAE classification job). */
    List<MarketLocation> findAllBySegmentAndSegmentAttemptsLessThan(MerchantSegment segment, int maxAttempts,
                                                                    Pageable limit);

    /** Already-classified markets still missing the IBGE municipality code
     *  (backfill — rows classified before the code was captured). */
    List<MarketLocation> findAllByIbgeCityCodeIsNullAndSegmentNotAndSegmentAttemptsLessThan(
            MerchantSegment segment, int maxAttempts, Pageable limit);

    /** Grey-zone merchants pending an admin verdict (no override, segment outside
     *  every supported/blocked bucket) — the admin review queue. */
    List<MarketLocation> findAllBySupportOverrideIsNullAndSegmentIn(List<MerchantSegment> segments);

    List<MarketLocation> findAllByCnpjRootAndChainIsNull(String cnpjRoot);

    /** Trade-name backfill queue: markets whose registry entry was never read, attempts capped. */
    List<MarketLocation> findAllByTradeNameCheckedAtIsNullAndTradeNameAttemptsLessThan(int maxAttempts,
                                                                                    Pageable limit);

    /** Trade names for the given CNPJs (only markets that have one). */
    @Query("select market.cnpj as cnpj, market.tradeName as tradeName from MarketLocation market "
            + "where market.cnpj in :cnpjs and market.tradeName is not null")
    List<MarketTradeName> findTradeNamesByCnpjIn(@Param("cnpjs") Collection<String> cnpjs);

    interface MarketTradeName {
        String getCnpj();

        String getTradeName();
    }

    long countByChain(MarketChain chain);
}
