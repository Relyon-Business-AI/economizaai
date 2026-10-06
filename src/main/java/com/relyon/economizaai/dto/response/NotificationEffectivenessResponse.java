package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.NotificationType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin report on notification EFFECTIVENESS over the last {@code windowDays}.
 * Complements the relevance-report (which lives in the telemetry log): this one
 * reads the {@code notifications} outbox alone, so it answers "which types /
 * texts / send-times actually get delivered and read". Rates are fractions
 * (0..1, scale 4). All time bucketing uses Brasília wall-clock.
 */
public record NotificationEffectivenessResponse(
        int windowDays,
        List<TypeEffectiveness> byType,
        List<HourBucket> byHour,
        List<DayOfWeekBucket> byDayOfWeek,
        List<TextEffectiveness> topTexts
) {
    /** Delivery/read performance for one notification type in the window. */
    public record TypeEffectiveness(
            NotificationType type,
            long sent,
            long delivered,
            long read,
            /** delivered / sent. */
            BigDecimal deliveryRate,
            /** read / sent. */
            BigDecimal readRate
    ) {}

    /** Sent + read counts for one hour of the day (0..23, Brasília). */
    public record HourBucket(
            int hour,
            long sent,
            long read
    ) {}

    /** Sent + read counts for one day of the week (1=Mon..7=Sun, ISO, Brasília). */
    public record DayOfWeekBucket(
            int dayOfWeek,
            long sent,
            long read
    ) {}

    /** Read performance of the top notification titles by send volume. */
    public record TextEffectiveness(
            String title,
            long sent,
            long read,
            /** read / sent. */
            BigDecimal readRate
    ) {}
}
