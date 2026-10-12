package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationSystemSetting;
import com.relyon.economizaai.model.enums.NotificationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface NotificationSystemSettingRepository extends JpaRepository<NotificationSystemSetting, UUID> {

    Optional<NotificationSystemSetting> findByJob(NotificationJob job);

    /**
     * Claim atômico do disparo do dia: só UMA instância/tick vence a troca do
     * last_run_date — a perdedora vê 0 linhas e não envia (sem duplicata).
     */
    @Modifying
    @Query("""
        UPDATE NotificationSystemSetting setting
        SET setting.lastRunDate = :today
        WHERE setting.job = :job AND (setting.lastRunDate IS NULL OR setting.lastRunDate < :today)
    """)
    int claimRun(@Param("job") NotificationJob job, @Param("today") LocalDate today);
}
