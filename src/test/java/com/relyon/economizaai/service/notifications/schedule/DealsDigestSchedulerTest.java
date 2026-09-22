package com.relyon.economizaai.service.notifications.schedule;
import org.mockito.Spy;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.priceindex.DealsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealsDigestSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private DealsService dealsService;
    @Mock private DealSurfaceStateRepository surfaceStateRepository;
    @Mock private NotificationService notificationService;
    @Mock private NotificationEventService eventService;
    @Mock private DigestScheduleService scheduleService;
    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    @Spy private LocalizedMessageService messageService = realMessageService();
    @InjectMocks private DealsDigestScheduler scheduler;

    private final CollaborativeProperties properties = new CollaborativeProperties();
    private final UUID productId = UUID.randomUUID();
    private final UUID otherProductId = UUID.randomUUID();
    private final int currentHour = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).getHour();

    @BeforeEach
    void setUp() {
        // @InjectMocks won't inject the concrete properties bean; set it directly.
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "properties", properties);
    }

    private User dueUser(DigestFrequency frequency, OffsetDateTime lastSent) {
        return User.builder().id(UUID.randomUUID()).email("due@example.com")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .digestFrequency(frequency)
                .lastDigestSentAt(lastSent)
                .build();
    }

    private DealResponse deal(UUID product, double discountFraction, BigDecimal currentPrice) {
        return new DealResponse(product, "Café", null, "GROCERY", "12345678000199", "Mercado X",
                currentPrice, new BigDecimal("10.00"), new BigDecimal("2.00"),
                new BigDecimal("20.00"), BigDecimal.valueOf(discountFraction),
                3L, null, false, LocalDateTime.now());
    }

    @Test
    void dueUserWithNewsworthyDeal_sendsOneNotificationAndRecordsEverything() {
        var user = dueUser(DigestFrequency.DAILY, null);
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of(user));
        when(scheduleService.effectiveSendHour(user)).thenReturn(currentHour);
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(deal(productId, 0.22, new BigDecimal("7.80")),
                        deal(otherProductId, 0.10, new BigDecimal("9.00"))));
        // No prior state -> both newsworthy.
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(notificationService.notify(any(NotificationPayload.class)))
                .thenReturn(Notification.builder().id(UUID.randomUUID()).build());

        scheduler.run();

        var payloadCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService, times(1)).notify(payloadCaptor.capture());
        assertEquals(NotificationType.DEALS_DIGEST, payloadCaptor.getValue().type());
        // One SENT event + one state upsert per newsworthy deal (2 each).
        verify(eventService, times(2)).record(eq(user), eq(NotificationEventType.SENT), any());
        verify(surfaceStateRepository, times(2)).save(any(DealSurfaceState.class));
        // 1/day cap marked on the user.
        verify(userRepository).save(user);
    }

    @Test
    void userAlreadySentToday_isSkipped() {
        var sentTodayAt = OffsetDateTime.now(ZoneId.of("America/Sao_Paulo")).withHour(1);
        var user = dueUser(DigestFrequency.DAILY, sentTodayAt);
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of(user));
        lenient().when(scheduleService.effectiveSendHour(user)).thenReturn(currentHour);

        scheduler.run();

        verify(notificationService, never()).notify(any());
        verify(dealsService, never()).findDeals(any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void zeroNewsworthy_sendsNothing() {
        var user = dueUser(DigestFrequency.DAILY, null);
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of(user));
        when(scheduleService.effectiveSendHour(user)).thenReturn(currentHour);
        var standing = deal(productId, 0.20, new BigDecimal("8.00"));
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(standing));
        // Prior state: same discount, surfaced just now -> NOT newsworthy.
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), eq(productId), any()))
                .thenReturn(Optional.of(DealSurfaceState.builder()
                        .lastDiscountFraction(new BigDecimal("0.2000"))
                        .lastUnitPrice(new BigDecimal("8.00"))
                        .lastSurfacedAt(OffsetDateTime.now())
                        .build()));

        scheduler.run();

        verify(notificationService, never()).notify(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void improvedDiscountByFivePoints_isNewsworthy() {
        var user = dueUser(DigestFrequency.DAILY, null);
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of(user));
        when(scheduleService.effectiveSendHour(user)).thenReturn(currentHour);
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(deal(productId, 0.26, new BigDecimal("7.40"))));
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), eq(productId), any()))
                .thenReturn(Optional.of(DealSurfaceState.builder()
                        .lastDiscountFraction(new BigDecimal("0.2000"))
                        .lastUnitPrice(new BigDecimal("8.00"))
                        .lastSurfacedAt(OffsetDateTime.now())
                        .build()));
        when(notificationService.notify(any(NotificationPayload.class)))
                .thenReturn(Notification.builder().id(UUID.randomUUID()).build());

        scheduler.run();

        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void lapsedDealBeyondLookback_isNewsworthy() {
        var user = dueUser(DigestFrequency.DAILY, null);
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of(user));
        when(scheduleService.effectiveSendHour(user)).thenReturn(currentHour);
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(deal(productId, 0.20, new BigDecimal("8.00"))));
        // Same discount but last surfaced long before the lookback window -> newsworthy again.
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), eq(productId), any()))
                .thenReturn(Optional.of(DealSurfaceState.builder()
                        .lastDiscountFraction(new BigDecimal("0.2000"))
                        .lastUnitPrice(new BigDecimal("8.00"))
                        .lastSurfacedAt(OffsetDateTime.now().minusDays(
                                properties.getCollaborative().getLookbackDays() + 5))
                        .build()));
        when(notificationService.notify(any(NotificationPayload.class)))
                .thenReturn(Notification.builder().id(UUID.randomUUID()).build());

        scheduler.run();

        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void offFrequencyUser_isNeverACandidate() {
        // OFF users aren't returned by the candidate query at all.
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of());

        scheduler.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void oneFailingUserDoesNotAbortBatch() {
        var failing = dueUser(DigestFrequency.DAILY, null);
        var healthy = dueUser(DigestFrequency.DAILY, null);
        when(userRepository.findDigestCandidates(DigestFrequency.OFF)).thenReturn(List.of(failing, healthy));
        when(scheduleService.effectiveSendHour(any())).thenReturn(currentHour);
        when(dealsService.findDeals(eq(failing), anyBoolean(), isNull(), eq(50)))
                .thenThrow(new RuntimeException("boom"));
        when(dealsService.findDeals(eq(healthy), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(deal(productId, 0.22, new BigDecimal("7.80"))));
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(notificationService.notify(any(NotificationPayload.class)))
                .thenReturn(Notification.builder().id(UUID.randomUUID()).build());

        scheduler.run();

        // The healthy user still got their digest despite the first user blowing up.
        verify(notificationService, times(1)).notify(any());
    }
}
