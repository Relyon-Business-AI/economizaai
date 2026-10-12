package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.NotificationInsightsResponse;
import com.relyon.economizaai.dto.response.NotificationInsightsResponse.ConversionBlock;
import com.relyon.economizaai.dto.response.NotificationInsightsResponse.EngagementSplit;
import com.relyon.economizaai.dto.response.NotificationInsightsResponse.HourBucket;
import com.relyon.economizaai.dto.response.NotificationInsightsResponse.TypeInsights;
import com.relyon.economizaai.dto.response.NotificationInsightsResponse.UfInsights;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationEventRepository.TypeEventTally;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Painel "TUDO sobre notificações": junta a outbox (enviadas/entregues/lidas)
 * com a telemetria (aberturas/taps/conversões) por tipo, hora local e UF do
 * destinatário. Só leitura — toda métrica sai de query, nada é pré-agregado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationInsightsService {

    private final NotificationRepository notificationRepository;
    private final NotificationEventRepository eventRepository;

    @Transactional(readOnly = true)
    public NotificationInsightsResponse insights(int days) {
        var windowDays = Math.max(1, days);
        var since = BrazilClock.today().minusDays(windowDays).atStartOfDay();
        var sinceOffset = since.atOffset(ZoneOffset.UTC);

        var byType = buildByType(since);
        var byHour = buildHourBuckets(sinceOffset);
        var byUf = buildByUf(since);
        var engagement = buildEngagementSplit(since, sinceOffset);
        var conversions = buildConversions(sinceOffset);

        log.info("notification.insights windowDays={} types={} recipients={}",
                windowDays, byType.size(), engagement.recipients());
        return new NotificationInsightsResponse(windowDays, byType, byHour,
                bestHour(byHour), byUf, engagement, conversions);
    }

    private List<TypeInsights> buildByType(LocalDateTime since) {
        var eventsByType = eventRepository.tallyEventsByNotificationType(since).stream()
                .collect(Collectors.groupingBy(TypeEventTally::getType));
        var lines = new ArrayList<TypeInsights>();
        for (var tally : notificationRepository.tallyByTypeSince(since)) {
            var events = eventsByType.getOrDefault(tally.getType(), List.of());
            var opened = distinctUsers(events, NotificationEventType.PUSH_OPENED);
            var tapped = distinctUsers(events, NotificationEventType.DEAL_TAPPED);
            lines.add(new TypeInsights(
                    tally.getType(), tally.getSent(), tally.getDelivered(), tally.getRead(),
                    opened, tapped,
                    rate(tally.getDelivered(), tally.getSent()),
                    rate(tally.getRead(), tally.getSent()),
                    rate(opened, tally.getSent())));
        }
        lines.sort((left, right) -> Long.compare(right.sent(), left.sent()));
        return lines;
    }

    private List<HourBucket> buildHourBuckets(OffsetDateTime since) {
        var opensByHour = new long[24];
        var tapsByHour = new long[24];
        for (var tally : eventRepository.engagementByLocalHourSince(since)) {
            opensByHour[tally.getBucket()] = tally.getOpens();
            tapsByHour[tally.getBucket()] = tally.getTaps();
        }
        var buckets = new ArrayList<HourBucket>(24);
        for (var hour = 0; hour < 24; hour++) {
            buckets.add(new HourBucket(hour, opensByHour[hour], tapsByHour[hour]));
        }
        return buckets;
    }

    private Integer bestHour(List<HourBucket> buckets) {
        return buckets.stream()
                .filter(bucket -> bucket.opens() + bucket.taps() > 0)
                .max((left, right) -> Long.compare(left.opens() + left.taps(), right.opens() + right.taps()))
                .map(HourBucket::hour)
                .orElse(null);
    }

    private List<UfInsights> buildByUf(LocalDateTime since) {
        return notificationRepository.tallyByUfSince(since).stream()
                .map(tally -> new UfInsights(tally.getUf(), tally.getSent(), tally.getDelivered(),
                        tally.getRead(), rate(tally.getRead(), tally.getSent())))
                .toList();
    }

    private EngagementSplit buildEngagementSplit(LocalDateTime since,
                                                 OffsetDateTime sinceOffset) {
        var recipients = notificationRepository.countDistinctRecipientsSince(since);
        var engaged = eventRepository.countEngagedRecipients(sinceOffset, since);
        return new EngagementSplit(recipients, engaged, Math.max(0, recipients - engaged),
                rate(engaged, recipients));
    }

    private ConversionBlock buildConversions(OffsetDateTime since) {
        var conversions = 0L;
        var savings = BigDecimal.ZERO;
        for (var tally : eventRepository.countByTypeSince(since)) {
            if (tally.getEventType() == NotificationEventType.CONVERTED) {
                conversions = tally.getOccurrences();
                savings = tally.getSavings();
            }
        }
        return new ConversionBlock(conversions, savings);
    }

    private long distinctUsers(List<TypeEventTally> events, NotificationEventType type) {
        return events.stream()
                .filter(tally -> tally.getEventType() == type)
                .mapToLong(TypeEventTally::getUsers)
                .sum();
    }

    private static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
