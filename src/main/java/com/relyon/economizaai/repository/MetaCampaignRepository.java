package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MetaCampaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetaCampaignRepository extends JpaRepository<MetaCampaign, UUID> {

    /** Used by the sync job to upsert one campaign snapshot. */
    Optional<MetaCampaign> findByCampaignId(String campaignId);

    List<MetaCampaign> findAll();
}
