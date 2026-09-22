package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketLocationRepository extends JpaRepository<MarketLocation, UUID> {

    Optional<MarketLocation> findByCnpj(String cnpj);

    List<MarketLocation> findAllByCnpjIn(List<String> cnpjs);

    /** Markets we know about but haven't successfully geocoded yet (for the
     *  scheduled geocoding job). */
    List<MarketLocation> findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(int maxAttempts);

    /** Markets whose business segment isn't resolved yet (for the scheduled
     *  CNAE classification job). */
    List<MarketLocation> findAllBySegmentAndSegmentAttemptsLessThan(MerchantSegment segment, int maxAttempts);

    /** Already-classified markets still missing the IBGE municipality code
     *  (backfill — rows classified before the code was captured). */
    List<MarketLocation> findAllByIbgeCityCodeIsNullAndSegmentNotAndSegmentAttemptsLessThan(
            MerchantSegment segment, int maxAttempts);

    /** Grey-zone merchants pending an admin verdict (no override, segment outside
     *  every supported/blocked bucket) — the admin review queue. */
    List<MarketLocation> findAllBySupportOverrideIsNullAndSegmentIn(List<MerchantSegment> segments);
}
