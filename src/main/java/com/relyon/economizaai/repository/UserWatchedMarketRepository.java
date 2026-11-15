package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.UserWatchedMarket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserWatchedMarketRepository extends JpaRepository<UserWatchedMarket, UUID> {

    List<UserWatchedMarket> findAllByUserId(UUID userId);

    /** Every pin made by any member of the household — the watchlist is shared by the household. */
    List<UserWatchedMarket> findAllByUserHouseholdId(UUID householdId);

    /** Removes the market from the household's watchlist, whichever member pinned it. */
    void deleteByUserHouseholdIdAndMarketCnpj(UUID householdId, String marketCnpj);
}
