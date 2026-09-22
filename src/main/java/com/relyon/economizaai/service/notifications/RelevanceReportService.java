package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.RelevanceReportResponse;
import com.relyon.economizaai.dto.response.RelevanceReportResponse.Engagement;
import com.relyon.economizaai.dto.response.RelevanceReportResponse.Suppression;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationEventRepository.RelevanceSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the relevance-filter validation report from the telemetry log —
 * everything is derived from {@code notification_events}, so the analysis works
 * identically whether the filter ran in SHADOW or ON (no extra bookkeeping).
 *
 * <p>The regret analysis joins, per (user, product), each DISMISSED/MUTED
 * signal with the engagement events (DEAL_TAPPED / ADDED_TO_LIST / CONVERTED)
 * that occurred AFTER the signal and INSIDE its suppression window — exactly
 * the interactions the filter would have made impossible had it been ON.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelevanceReportService {

    private final NotificationEventRepository eventRepository;
    private final DealFeedbackService dealFeedbackService;

    @Transactional(readOnly = true)
    public RelevanceReportResponse report(int windowDays) {
        var since = OffsetDateTime.now().minusDays(windowDays);
        var engagement = buildEngagement(since);
        var suppression = buildSuppression(since);
        log.info("relevance.report windowDays={} mode={} signals={} regret={}",
                windowDays, dealFeedbackService.mode(), suppression.signals(), suppression.regretEngagements());
        return new RelevanceReportResponse(
                dealFeedbackService.mode().name(),
                windowDays,
                dealFeedbackService.dismissedDays(),
                dealFeedbackService.mutedDays(),
                engagement,
                suppression);
    }

    private Engagement buildEngagement(OffsetDateTime since) {
        var counts = new EnumMap<NotificationEventType, Long>(NotificationEventType.class);
        var savings = BigDecimal.ZERO;
        for (var row : eventRepository.countByTypeSince(since)) {
            counts.put(row.getEventType(), row.getOccurrences());
            if (row.getEventType() == NotificationEventType.CONVERTED) {
                savings = row.getSavings();
            }
        }
        var views = counts.getOrDefault(NotificationEventType.DEAL_VIEWED, 0L);
        var taps = counts.getOrDefault(NotificationEventType.DEAL_TAPPED, 0L);
        var dismissed = counts.getOrDefault(NotificationEventType.DISMISSED, 0L);
        return new Engagement(
                counts.getOrDefault(NotificationEventType.SENT, 0L),
                counts.getOrDefault(NotificationEventType.PUSH_OPENED, 0L),
                counts.getOrDefault(NotificationEventType.SCREEN_OPENED, 0L),
                views,
                taps,
                counts.getOrDefault(NotificationEventType.ADDED_TO_LIST, 0L),
                dismissed,
                counts.getOrDefault(NotificationEventType.MUTED, 0L),
                counts.getOrDefault(NotificationEventType.CONVERTED, 0L),
                savings,
                rate(taps, views),
                rate(dismissed, views));
    }

    private Suppression buildSuppression(OffsetDateTime since) {
        // Look back far enough that a signal BEFORE the window still suppresses
        // engagements INSIDE it — otherwise early-window regret goes unseen.
        var signalHorizon = since.minusDays(dealFeedbackService.mutedDays());
        var rows = eventRepository.findRelevanceSignals(signalHorizon);

        var suppressionSignals = rows.stream().filter(this::isSuppressionSignal).toList();
        var engagements = rows.stream().filter(row -> !isSuppressionSignal(row)).toList();

        Map<UserProductKey, List<RelevanceSignal>> signalsByKey = new HashMap<>();
        for (var signal : suppressionSignals) {
            signalsByKey.computeIfAbsent(UserProductKey.of(signal), key -> new ArrayList<>())
                    .add(signal);
        }

        var regretEngagements = 0L;
        var regretSavings = BigDecimal.ZERO;
        for (var engagement : engagements) {
            if (engagement.getOccurredAt().isBefore(since)) continue; // only window engagements count
            var signals = signalsByKey.get(UserProductKey.of(engagement));
            if (signals == null || !isRegretted(engagement, signals)) continue;
            regretEngagements++;
            if (engagement.getEventType() == NotificationEventType.CONVERTED
                    && engagement.getSavingsAmount() != null) {
                regretSavings = regretSavings.add(engagement.getSavingsAmount());
            }
        }

        var windowSignals = suppressionSignals.stream()
                .filter(signal -> !signal.getOccurredAt().isBefore(since))
                .toList();
        var users = new HashSet<UUID>();
        var products = new HashSet<UUID>();
        for (var signal : windowSignals) {
            users.add(signal.getUserId());
            products.add(signal.getProductId());
        }
        return new Suppression(windowSignals.size(), users.size(), products.size(),
                regretEngagements, regretSavings);
    }

    /** The engagement happened after a signal and inside that signal's suppression window. */
    private boolean isRegretted(RelevanceSignal engagement, List<RelevanceSignal> signals) {
        return signals.stream().anyMatch(signal -> {
            var windowDays = signal.getEventType() == NotificationEventType.MUTED
                    ? dealFeedbackService.mutedDays()
                    : dealFeedbackService.dismissedDays();
            var suppressedUntil = signal.getOccurredAt().plusDays(windowDays);
            return engagement.getOccurredAt().isAfter(signal.getOccurredAt())
                    && !engagement.getOccurredAt().isAfter(suppressedUntil);
        });
    }

    private boolean isSuppressionSignal(RelevanceSignal row) {
        return row.getEventType() == NotificationEventType.DISMISSED
                || row.getEventType() == NotificationEventType.MUTED;
    }

    private static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private record UserProductKey(UUID userId, UUID productId) {
        static UserProductKey of(RelevanceSignal signal) {
            return new UserProductKey(signal.getUserId(), signal.getProductId());
        }
    }
}
