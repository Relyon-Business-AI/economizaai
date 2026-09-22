package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdMarketAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdMarketAliasRepository extends JpaRepository<HouseholdMarketAlias, UUID> {

    Optional<HouseholdMarketAlias> findByHouseholdIdAndMarketCnpj(UUID householdId, String marketCnpj);

    List<HouseholdMarketAlias> findAllByHouseholdId(UUID householdId);

    List<HouseholdMarketAlias> findAllByOriginHouseholdId(UUID householdId);

    List<HouseholdMarketAlias> findAllByHouseholdIdAndMarketCnpjIn(UUID householdId, Collection<String> marketCnpjs);

    void deleteByHouseholdIdAndMarketCnpj(UUID householdId, String marketCnpj);
}
