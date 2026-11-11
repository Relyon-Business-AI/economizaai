package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.CostAlert;
import com.relyon.economizaai.model.enums.CostAlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CostAlertRepository extends JpaRepository<CostAlert, UUID> {

    boolean existsByAlertKeyAndThresholdAndPeriodKey(String alertKey, int threshold, String periodKey);

    /** Alerts still owed to the admin whose next delivery attempt is due. */
    List<CostAlert> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(CostAlertStatus status,
                                                                                 OffsetDateTime dueAt);
}
