package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.NotificationSystemSetting;
import com.relyon.economizaai.model.enums.NotificationJob;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Configuração de uma notificação de sistema + os defaults do i18n (pra UI
 * mostrar como placeholder quando não há override) e o que o job suporta
 * editar (agenda/templates).
 */
public record NotificationSettingResponse(
        NotificationJob job,
        boolean enabled,
        Integer sendHour,
        Integer dayOfWeek,
        Integer dayOfMonth,
        String titlePt,
        String titleEn,
        String bodyPt,
        String bodyEn,
        String defaultTitlePt,
        String defaultBodyPt,
        boolean supportsSchedule,
        boolean supportsTemplates,
        LocalDate lastRunDate,
        String updatedBy,
        LocalDateTime updatedAt
) {
    public static NotificationSettingResponse from(NotificationSystemSetting setting,
                                                   String defaultTitlePt, String defaultBodyPt,
                                                   boolean supportsSchedule, boolean supportsTemplates) {
        return new NotificationSettingResponse(
                setting.getJob(), setting.isEnabled(),
                setting.getSendHour(), setting.getDayOfWeek(), setting.getDayOfMonth(),
                setting.getTitlePt(), setting.getTitleEn(), setting.getBodyPt(), setting.getBodyEn(),
                defaultTitlePt, defaultBodyPt, supportsSchedule, supportsTemplates,
                setting.getLastRunDate(), setting.getUpdatedBy(), setting.getUpdatedAt());
    }
}
