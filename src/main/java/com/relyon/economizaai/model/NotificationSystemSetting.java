package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.NotificationJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * Configuração editável (dashboard) de uma notificação de SISTEMA. Os jobs
 * leem a linha a cada execução: liga/desliga, agenda (hora local Brasília,
 * dia da semana/mês quando aplicável) e override de template (null = default
 * do i18n; placeholders {0},{1}… iguais aos das mensagens).
 */
@Entity
@Table(name = "notification_system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class NotificationSystemSetting extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, unique = true)
    private NotificationJob job;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean enabled = true;

    @Column(name = "send_hour")
    private Integer sendHour;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "title_pt", columnDefinition = "TEXT")
    private String titlePt;

    @Column(name = "title_en", columnDefinition = "TEXT")
    private String titleEn;

    @Column(name = "body_pt", columnDefinition = "TEXT")
    private String bodyPt;

    @Column(name = "body_en", columnDefinition = "TEXT")
    private String bodyEn;

    /** Marcador de claim do tick horário — evita envio duplicado (restart/2 instâncias). */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
