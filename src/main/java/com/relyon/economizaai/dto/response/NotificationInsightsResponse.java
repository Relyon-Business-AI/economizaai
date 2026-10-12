package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.NotificationType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Painel completo de performance das notificações (orgânicas + campanhas):
 * funil por tipo (com abertura/tap atribuídos), engajamento por hora local e
 * melhor horário, corte por UF do destinatário e recorte engajados/silenciosos.
 * Taxas são frações de {@code sent} (4 casas).
 */
public record NotificationInsightsResponse(
        int windowDays,
        List<TypeInsights> byType,
        List<HourBucket> engagementByHour,
        Integer bestHour,
        List<UfInsights> byUf,
        EngagementSplit engagement,
        ConversionBlock conversions
) {

    public record TypeInsights(
            NotificationType type,
            long sent,
            long delivered,
            long read,
            long opened,
            long tapped,
            BigDecimal deliveryRate,
            BigDecimal readRate,
            BigDecimal openRate
    ) {}

    public record HourBucket(int hour, long opens, long taps) {}

    public record UfInsights(String uf, long sent, long delivered, long read, BigDecimal readRate) {}

    /** Quem recebeu e engajou (abriu/tocou em algo) vs quem ficou em silêncio na janela. */
    public record EngagementSplit(long recipients, long engaged, long silent, BigDecimal engagementRate) {}

    /** Conversões atribuídas na janela (evento CONVERTED) + R$ economizado. */
    public record ConversionBlock(long conversions, BigDecimal attributedSavings) {}
}
