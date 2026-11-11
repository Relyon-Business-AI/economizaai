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
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock private NotificationSettingsService settingsService;
    @InjectMocks private DealsDigestScheduler scheduler;

    private final CollaborativeProperties properties = new CollaborativeProperties();
    private final UUID productId = UUID.randomUUID();
    private final UUID otherProductId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // @InjectMocks won't inject the concrete properties bean; set it directly.
        ReflectionTestUtils.setField(scheduler, "properties", properties);
        lenient().when(settingsService.isEnabled(NotificationJob.DEALS_DIGEST)).thenReturn(true);
        lenient().when(userRepository.claimDigestSlot(any(), any(), any())).thenReturn(1);
    }

    /** The SQL due-selection returns these users on the first page, then nothing. */
    private void dueUsers(User... users) {
        when(userRepository.findDigestDuePage(anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(users)).thenReturn(List.of());
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
        dueUsers(user);
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
        // 1/day cap taken with a targeted claim — never a full save of the user row.
        verify(userRepository).claimDigestSlot(eq(user.getId()), any(), any());
        verify(userRepository, never()).save(any());
        verify(scheduleService).updateSendHourFromShoppingHabits(user);
    }

    @Test
    void claimsTheSlotThenDispatchesBeforePersistingState() {
        // The day's slot is claimed first (so a second run cannot send too); the tx boundary
        // sits AFTER the send: notify() (network) runs before the per-deal state upserts and
        // telemetry rows are persisted, so a slow push never pins a connection through the writes.
        var user = dueUser(DigestFrequency.DAILY, null);
        dueUsers(user);
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(deal(productId, 0.22, new BigDecimal("7.80"))));
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(notificationService.notify(any(NotificationPayload.class)))
                .thenReturn(Notification.builder().id(UUID.randomUUID()).build());

        scheduler.run();

        var inOrder = inOrder(notificationService, surfaceStateRepository, eventService, userRepository);
        inOrder.verify(userRepository).claimDigestSlot(eq(user.getId()), any(), any());
        inOrder.verify(notificationService).notify(any(NotificationPayload.class));
        inOrder.verify(surfaceStateRepository).save(any(DealSurfaceState.class));
        inOrder.verify(eventService).record(eq(user), eq(NotificationEventType.SENT), any());
    }

    @Test
    void slotAlreadyTakenByAnotherRun_sendsNothing() {
        var user = dueUser(DigestFrequency.DAILY, null);
        dueUsers(user);
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(deal(productId, 0.22, new BigDecimal("7.80"))));
        when(surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(userRepository.claimDigestSlot(eq(user.getId()), any(), any())).thenReturn(0);

        scheduler.run();

        verify(notificationService, never()).notify(any());
        verify(surfaceStateRepository, never()).save(any());
    }

    @Test
    void selectsDueUsersInSqlByReachedHourAndTodaysBoundary() {
        dueUsers();

        scheduler.run();

        var now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
        var hourCaptor = ArgumentCaptor.forClass(Integer.class);
        var startOfTodayCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(userRepository).findDigestDuePage(hourCaptor.capture(), anyBoolean(),
                startOfTodayCaptor.capture(), any(), any());
        assertEquals(now.getHour(), hourCaptor.getValue(), 1);
        var boundary = startOfTodayCaptor.getValue().atZoneSameInstant(ZoneId.of("America/Sao_Paulo"));
        assertEquals(0, boundary.getHour());
        assertEquals(0, boundary.getMinute());
        // Never loads every user, never derives the hour per user in the loop.
        verify(userRepository, never()).findDigestCandidates(any());
        verify(scheduleService).assignMissingSendHours();
    }

    @Test
    void walksEveryPageOfDueUsersUsingTheLastIdAsCursor() {
        var firstPageUser = dueUser(DigestFrequency.DAILY, null);
        var secondPageUser = dueUser(DigestFrequency.DAILY, null);
        when(userRepository.findDigestDuePage(anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(firstPageUser))
                .thenReturn(List.of(secondPageUser))
                .thenReturn(List.of());
        when(dealsService.findDeals(any(), anyBoolean(), isNull(), eq(50))).thenReturn(List.of());

        scheduler.run();

        verify(userRepository).findDigestDuePage(anyInt(), anyBoolean(), any(), eq(firstPageUser.getId()), any());
        verify(userRepository).findDigestDuePage(anyInt(), anyBoolean(), any(), eq(secondPageUser.getId()), any());
        verify(dealsService, times(2)).findDeals(any(), anyBoolean(), isNull(), eq(50));
    }

    @Test
    void zeroNewsworthy_sendsNothing() {
        var user = dueUser(DigestFrequency.DAILY, null);
        dueUsers(user);
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
        // An empty digest must not use up the once-a-day slot.
        verify(userRepository, never()).claimDigestSlot(any(), any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void improvedDiscountByFivePoints_isNewsworthy() {
        var user = dueUser(DigestFrequency.DAILY, null);
        dueUsers(user);
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
    void thresholdStepCrossedWithNullLastPaidPrice_isNewsworthy() {
        // User never bought the product (lastPaidPrice null): the stricter-bar
        // re-notification must still fire — the formula only needs unit prices.
        var user = dueUser(DigestFrequency.DAILY, null);
        dueUsers(user);
        var neverBoughtDeal = new DealResponse(productId, "Café", null, "GROCERY",
                "12345678000199", "Mercado X", new BigDecimal("7.00"), null,
                new BigDecimal("2.00"), new BigDecimal("20.00"), new BigDecimal("0.22"),
                3L, null, false, LocalDateTime.now());
        when(dealsService.findDeals(eq(user), anyBoolean(), isNull(), eq(50)))
                .thenReturn(List.of(neverBoughtDeal));
        // Discount improved only 2 p.p. (< 5 p.p. step), but the price dropped from
        // R$8.00 to R$7.00 -> the required-drop bar got stricter and was still cleared.
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
        dueUsers(user);
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
    void noDueUsers_sendsNothing() {
        dueUsers();

        scheduler.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void oneFailingUserDoesNotAbortBatch() {
        var failing = dueUser(DigestFrequency.DAILY, null);
        var healthy = dueUser(DigestFrequency.DAILY, null);
        dueUsers(failing, healthy);
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
