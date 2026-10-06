package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.NotificationRepository.TimeBucketTally;
import com.relyon.economizaai.repository.NotificationRepository.TitleTally;
import com.relyon.economizaai.repository.NotificationRepository.TypeTally;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNotificationEffectivenessServiceTest {

    @Mock private NotificationRepository notificationRepository;

    private AdminNotificationEffectivenessService service;

    @BeforeEach
    void setUp() {
        service = new AdminNotificationEffectivenessService(notificationRepository);
        lenient().when(notificationRepository.tallyByTypeSince(any())).thenReturn(List.of());
        lenient().when(notificationRepository.tallyByHourSince(any())).thenReturn(List.of());
        lenient().when(notificationRepository.tallyByDayOfWeekSince(any())).thenReturn(List.of());
        lenient().when(notificationRepository.topTitlesSince(any(), any())).thenReturn(List.of());
    }

    private TypeTally typeTally(NotificationType type, long sent, long delivered, long read) {
        return new TypeTally() {
            @Override public NotificationType getType() { return type; }
            @Override public long getSent() { return sent; }
            @Override public long getDelivered() { return delivered; }
            @Override public long getRead() { return read; }
        };
    }

    private TimeBucketTally timeBucketTally(int bucket, long sent, long read) {
        return new TimeBucketTally() {
            @Override public int getBucket() { return bucket; }
            @Override public long getSent() { return sent; }
            @Override public long getRead() { return read; }
        };
    }

    private TitleTally titleTally(String title, long sent, long read) {
        return new TitleTally() {
            @Override public String getTitle() { return title; }
            @Override public long getSent() { return sent; }
            @Override public long getRead() { return read; }
        };
    }

    @Test
    void byType_computesRatesAndGuardsDivisionByZero() {
        when(notificationRepository.tallyByTypeSince(any())).thenReturn(List.of(
                typeTally(NotificationType.PROMO_COMMUNITY, 10, 8, 4),
                typeTally(NotificationType.SYSTEM, 0, 0, 0)));

        var report = service.effectiveness(30);

        assertEquals(2, report.byType().size());
        // Sorted by sent desc — PROMO_COMMUNITY first.
        var promo = report.byType().get(0);
        assertEquals(NotificationType.PROMO_COMMUNITY, promo.type());
        assertEquals(10, promo.sent());
        assertEquals(8, promo.delivered());
        assertEquals(4, promo.read());
        assertEquals(new BigDecimal("0.8000"), promo.deliveryRate());
        assertEquals(new BigDecimal("0.4000"), promo.readRate());

        var system = report.byType().get(1);
        assertEquals(BigDecimal.ZERO, system.deliveryRate());
        assertEquals(BigDecimal.ZERO, system.readRate());
    }

    @Test
    void byHour_zeroFillsAll24BucketsInOrder() {
        when(notificationRepository.tallyByHourSince(any())).thenReturn(List.of(
                timeBucketTally(9, 5, 2),
                timeBucketTally(20, 3, 1)));

        var report = service.effectiveness(30);

        assertEquals(24, report.byHour().size());
        for (var hour = 0; hour < 24; hour++) {
            assertEquals(hour, report.byHour().get(hour).hour());
        }
        assertEquals(5, report.byHour().get(9).sent());
        assertEquals(2, report.byHour().get(9).read());
        assertEquals(3, report.byHour().get(20).sent());
        assertEquals(0, report.byHour().get(0).sent());
    }

    @Test
    void byDayOfWeek_zeroFillsSevenIsoBuckets() {
        when(notificationRepository.tallyByDayOfWeekSince(any())).thenReturn(List.of(
                timeBucketTally(1, 7, 3),   // Monday
                timeBucketTally(7, 2, 0))); // Sunday

        var report = service.effectiveness(30);

        assertEquals(7, report.byDayOfWeek().size());
        assertEquals(1, report.byDayOfWeek().get(0).dayOfWeek());
        assertEquals(7, report.byDayOfWeek().get(6).dayOfWeek());
        assertEquals(7, report.byDayOfWeek().get(0).sent());
        assertEquals(3, report.byDayOfWeek().get(0).read());
        assertEquals(2, report.byDayOfWeek().get(6).sent());
        assertEquals(0, report.byDayOfWeek().get(3).sent());
    }

    @Test
    void topTexts_mapsTitlesWithReadRate() {
        when(notificationRepository.topTitlesSince(any(), any())).thenReturn(List.of(
                titleTally("Oferta imperdível", 20, 5),
                titleTally("Resumo diário", 10, 0)));

        var report = service.effectiveness(30);

        assertEquals(2, report.topTexts().size());
        assertEquals("Oferta imperdível", report.topTexts().get(0).title());
        assertEquals(new BigDecimal("0.2500"), report.topTexts().get(0).readRate());
        // 0 reads over a non-zero send count is a computed 0.0000, not the divide-by-zero guard's ZERO.
        assertEquals(new BigDecimal("0.0000"), report.topTexts().get(1).readRate());
    }

    @Test
    void window_clampedToAtLeastOneDay() {
        var report = service.effectiveness(0);

        assertEquals(1, report.windowDays());
    }
}
