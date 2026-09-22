package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationEventRepository.RelevanceSignal;
import com.relyon.economizaai.repository.NotificationEventRepository.TypeCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelevanceReportServiceTest {

    @Mock private NotificationEventRepository eventRepository;
    @Mock private DealFeedbackService dealFeedbackService;

    private RelevanceReportService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RelevanceReportService(eventRepository, dealFeedbackService);
        lenient().when(dealFeedbackService.mode()).thenReturn(RelevanceMode.SHADOW);
        lenient().when(dealFeedbackService.dismissedDays()).thenReturn(14);
        lenient().when(dealFeedbackService.mutedDays()).thenReturn(180);
        lenient().when(eventRepository.countByTypeSince(any())).thenReturn(List.of());
        lenient().when(eventRepository.findRelevanceSignals(any())).thenReturn(List.of());
    }

    private TypeCount typeCount(NotificationEventType type, long occurrences, BigDecimal savings) {
        return new TypeCount() {
            @Override public NotificationEventType getEventType() { return type; }
            @Override public long getOccurrences() { return occurrences; }
            @Override public BigDecimal getSavings() { return savings; }
        };
    }

    private RelevanceSignal signal(NotificationEventType type, int daysAgo, BigDecimal savings) {
        var occurred = OffsetDateTime.now().minusDays(daysAgo);
        return new RelevanceSignal() {
            @Override public UUID getUserId() { return userId; }
            @Override public UUID getProductId() { return productId; }
            @Override public NotificationEventType getEventType() { return type; }
            @Override public OffsetDateTime getOccurredAt() { return occurred; }
            @Override public BigDecimal getSavingsAmount() { return savings; }
        };
    }

    @Test
    void engagement_countsAndRatesFromEventLog() {
        when(eventRepository.countByTypeSince(any())).thenReturn(List.of(
                typeCount(NotificationEventType.DEAL_VIEWED, 10, BigDecimal.ZERO),
                typeCount(NotificationEventType.DEAL_TAPPED, 4, BigDecimal.ZERO),
                typeCount(NotificationEventType.DISMISSED, 2, BigDecimal.ZERO),
                typeCount(NotificationEventType.SENT, 7, BigDecimal.ZERO),
                typeCount(NotificationEventType.CONVERTED, 1, new BigDecimal("12.50"))));

        var report = service.report(30);

        assertEquals("SHADOW", report.mode());
        assertEquals(10, report.engagement().dealViews());
        assertEquals(4, report.engagement().dealTaps());
        assertEquals(7, report.engagement().sent());
        assertEquals(1, report.engagement().conversions());
        assertEquals(new BigDecimal("12.50"), report.engagement().attributedSavings());
        assertEquals(new BigDecimal("0.4000"), report.engagement().tapThroughRate());
        assertEquals(new BigDecimal("0.2000"), report.engagement().dismissalRate());
    }

    @Test
    void engagement_zeroViewsYieldZeroRatesNotDivisionError() {
        var report = service.report(30);

        assertEquals(BigDecimal.ZERO, report.engagement().tapThroughRate());
        assertEquals(BigDecimal.ZERO, report.engagement().dismissalRate());
    }

    @Test
    void regret_engagementAfterSignalInsideItsWindowCounts() {
        when(eventRepository.findRelevanceSignals(any())).thenReturn(List.of(
                signal(NotificationEventType.DISMISSED, 10, null),
                signal(NotificationEventType.DEAL_TAPPED, 5, null)));

        var report = service.report(30);

        assertEquals(1, report.suppression().signals());
        assertEquals(1, report.suppression().regretEngagements());
    }

    @Test
    void regret_engagementBeforeTheSignalDoesNotCount() {
        when(eventRepository.findRelevanceSignals(any())).thenReturn(List.of(
                signal(NotificationEventType.DEAL_TAPPED, 12, null),
                signal(NotificationEventType.DISMISSED, 10, null)));

        var report = service.report(30);

        assertEquals(0, report.suppression().regretEngagements());
    }

    @Test
    void regret_engagementPastTheSuppressionWindowDoesNotCount() {
        // dismissal 40d ago suppresses until 26d ago; the tap 20d ago lands after
        // the suppression lapsed — the filter would NOT have hidden that deal.
        when(eventRepository.findRelevanceSignals(any())).thenReturn(List.of(
                signal(NotificationEventType.DISMISSED, 40, null),
                signal(NotificationEventType.DEAL_TAPPED, 20, null)));

        var report = service.report(30);

        assertEquals(0, report.suppression().regretEngagements());
        assertEquals(0, report.suppression().signals(), "signal predates the report window");
    }

    @Test
    void regret_convertedSavingsAreSummedAsTheCostOfBeingWrong() {
        when(eventRepository.findRelevanceSignals(any())).thenReturn(List.of(
                signal(NotificationEventType.MUTED, 10, null),
                signal(NotificationEventType.CONVERTED, 3, new BigDecimal("8.40")),
                signal(NotificationEventType.DEAL_TAPPED, 2, null)));

        var report = service.report(30);

        assertEquals(2, report.suppression().regretEngagements());
        assertEquals(new BigDecimal("8.40"), report.suppression().regretSavings());
        assertEquals(1, report.suppression().usersWithSignals());
        assertEquals(1, report.suppression().productsAffected());
    }

    @Test
    void report_echoesFilterConfiguration() {
        var report = service.report(7);

        assertEquals(7, report.windowDays());
        assertEquals(14, report.dismissedDays());
        assertEquals(180, report.mutedDays());
    }
}
