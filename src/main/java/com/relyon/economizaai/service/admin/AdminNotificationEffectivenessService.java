package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.NotificationEffectivenessResponse;
import com.relyon.economizaai.dto.response.NotificationEffectivenessResponse.DayOfWeekBucket;
import com.relyon.economizaai.dto.response.NotificationEffectivenessResponse.HourBucket;
import com.relyon.economizaai.dto.response.NotificationEffectivenessResponse.TextEffectiveness;
import com.relyon.economizaai.dto.response.NotificationEffectivenessResponse.TypeEffectiveness;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.NotificationRepository.TimeBucketTally;
import com.relyon.economizaai.repository.NotificationRepository.TitleTally;
import com.relyon.economizaai.repository.NotificationRepository.TypeTally;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only notification-effectiveness analytics for the admin dashboard. Every
 * number comes from the {@code notifications} outbox table alone: sent = row
 * count, delivered = {@code delivered} true, read = {@code readAt} not null.
 * Windows are anchored on Brasília's "today minus days" at start-of-day, and the
 * hour / day-of-week buckets use Brasília wall-clock (see the native queries).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationEffectivenessService {

    private static final int TOP_TITLES = 15;

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public NotificationEffectivenessResponse effectiveness(int days) {
        var windowDays = Math.max(1, days);
        var since = BrazilClock.today().minusDays(windowDays).atStartOfDay();

        var byType = buildByType(since);
        var byHour = buildHourBuckets(since);
        var byDayOfWeek = buildDayOfWeekBuckets(since);
        var topTexts = buildTopTexts(since);

        var totalSent = byType.stream().mapToLong(TypeEffectiveness::sent).sum();
        log.info("notification.effectiveness windowDays={} types={} sent={} topTexts={}",
                windowDays, byType.size(), totalSent, topTexts.size());
        return new NotificationEffectivenessResponse(windowDays, byType, byHour, byDayOfWeek, topTexts);
    }

    private List<TypeEffectiveness> buildByType(LocalDateTime since) {
        var lines = new ArrayList<TypeEffectiveness>();
        for (var tally : notificationRepository.tallyByTypeSince(since)) {
            lines.add(new TypeEffectiveness(
                    tally.getType(), tally.getSent(), tally.getDelivered(), tally.getRead(),
                    rate(tally.getDelivered(), tally.getSent()),
                    rate(tally.getRead(), tally.getSent())));
        }
        lines.sort((left, right) -> Long.compare(right.sent(), left.sent()));
        return lines;
    }

    private List<HourBucket> buildHourBuckets(LocalDateTime since) {
        var sentByHour = new long[24];
        var readByHour = new long[24];
        for (var tally : notificationRepository.tallyByHourSince(since)) {
            var hour = tally.getBucket();
            sentByHour[hour] = tally.getSent();
            readByHour[hour] = tally.getRead();
        }
        var buckets = new ArrayList<HourBucket>(24);
        for (var hour = 0; hour < 24; hour++) {
            buckets.add(new HourBucket(hour, sentByHour[hour], readByHour[hour]));
        }
        return buckets;
    }

    private List<DayOfWeekBucket> buildDayOfWeekBuckets(LocalDateTime since) {
        var sentByDay = new long[8]; // index 1..7 (ISO)
        var readByDay = new long[8];
        for (var tally : notificationRepository.tallyByDayOfWeekSince(since)) {
            var dayOfWeek = tally.getBucket();
            sentByDay[dayOfWeek] = tally.getSent();
            readByDay[dayOfWeek] = tally.getRead();
        }
        var buckets = new ArrayList<DayOfWeekBucket>(7);
        for (var dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) {
            buckets.add(new DayOfWeekBucket(dayOfWeek, sentByDay[dayOfWeek], readByDay[dayOfWeek]));
        }
        return buckets;
    }

    private List<TextEffectiveness> buildTopTexts(LocalDateTime since) {
        var lines = new ArrayList<TextEffectiveness>();
        for (var tally : notificationRepository.topTitlesSince(since, PageRequest.of(0, TOP_TITLES))) {
            lines.add(new TextEffectiveness(
                    tally.getTitle(), tally.getSent(), tally.getRead(),
                    rate(tally.getRead(), tally.getSent())));
        }
        return lines;
    }

    private static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
