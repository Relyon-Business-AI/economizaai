package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Atualização de uma notificação de sistema. Agenda só vale pros jobs que têm
 * (semanal: dayOfWeek; mensal: dayOfMonth); templates vazios/null = default i18n.
 */
public record UpdateNotificationSettingRequest(
        @NotNull Boolean enabled,
        @Min(0) @Max(23) Integer sendHour,
        @Min(1) @Max(7) Integer dayOfWeek,
        @Min(1) @Max(28) Integer dayOfMonth,
        @Size(max = 255) String titlePt,
        @Size(max = 255) String titleEn,
        @Size(max = 2000) String bodyPt,
        @Size(max = 2000) String bodyEn
) {}
