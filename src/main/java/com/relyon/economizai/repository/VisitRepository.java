package com.relyon.economizai.repository;

import com.relyon.economizai.model.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {

    @Query("SELECT count(visit) FROM Visit visit WHERE visit.createdAt >= :since")
    long countSince(LocalDateTime since);

    @Query("SELECT count(distinct visit.anonId) FROM Visit visit WHERE visit.createdAt >= :since")
    long countUniqueVisitorsSince(LocalDateTime since);

    /** (campaign, source, medium, acquisitionChannel, uniqueVisitors) since the window start. */
    @Query("SELECT visit.utmCampaign, visit.utmSource, visit.utmMedium, visit.acquisitionChannel, "
            + "count(distinct visit.anonId) FROM Visit visit WHERE visit.createdAt >= :since "
            + "GROUP BY visit.utmCampaign, visit.utmSource, visit.utmMedium, visit.acquisitionChannel")
    List<Object[]> campaignTotalsSince(LocalDateTime since);
}
