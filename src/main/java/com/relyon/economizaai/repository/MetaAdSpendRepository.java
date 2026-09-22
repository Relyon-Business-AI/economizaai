package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MetaAdSpend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetaAdSpendRepository extends JpaRepository<MetaAdSpend, UUID> {

    /** Used by the sync job to upsert one campaign/day row. */
    Optional<MetaAdSpend> findByCampaignIdAndSpendDate(String campaignId, LocalDate spendDate);

    List<MetaAdSpend> findBySpendDateBetweenOrderBySpendDateAsc(LocalDate from, LocalDate to);

    @Query("select coalesce(sum(spend.spend), 0) from MetaAdSpend spend "
            + "where spend.spendDate between :from and :to")
    BigDecimal totalSpendBetween(LocalDate from, LocalDate to);

    /** Per-campaign spend totals in the window — [campaignId, campaignName, totalSpend, clicks, impressions]. */
    @Query("select spend.campaignId, max(spend.campaignName), sum(spend.spend), sum(spend.clicks), sum(spend.impressions) "
            + "from MetaAdSpend spend where spend.spendDate between :from and :to group by spend.campaignId")
    List<Object[]> campaignTotalsBetween(LocalDate from, LocalDate to);
}
