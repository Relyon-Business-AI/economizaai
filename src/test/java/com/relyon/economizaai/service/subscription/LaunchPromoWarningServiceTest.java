package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.LaunchPromoWarning;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.repository.LaunchPromoWarningRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaunchPromoWarningServiceTest {

    private static final LocalDate PROMO_UNTIL = LocalDate.of(2026, 12, 31);

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private LaunchPromoWarningRepository launchPromoWarningRepository;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService localizedMessageService;

    private CollaborativeProperties properties;
    private LaunchPromoWarningService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        properties.getSubscription().getPromo().setUntil(PROMO_UNTIL);
        service = new LaunchPromoWarningService(properties, subscriptionRepository, launchPromoWarningRepository,
                notificationService, localizedMessageService);
        lenient().when(localizedMessageService.translate(anyString(), any(Locale.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "|" + invocation.getArgument(2));
    }

    private Subscription promoHolder(long idLeastBits) {
        var user = User.builder().id(UUID.randomUUID()).email("holder" + idLeastBits + "@economizaai.app")
                .locale("pt").build();
        return Subscription.builder().id(new UUID(0L, idLeastBits)).user(user)
                .status(SubscriptionStatus.ACTIVE).provider("manual").build();
    }

    /** One page of holders for the milestone, then an empty page. */
    private void holdersToWarn(int milestoneDays, Subscription... holders) {
        when(subscriptionRepository.findLaunchPromoHoldersToWarn(any(), eq(milestoneDays), any(), any()))
                .thenReturn(List.of(holders))
                .thenReturn(List.of());
    }

    @Test
    void warnsAtEachMilestoneDay() {
        var holder = promoHolder(1);
        for (var milestoneDays : List.of(15, 7, 3, 1)) {
            holdersToWarn(milestoneDays, holder);
            service.warnPromoHolders(PROMO_UNTIL.minusDays(milestoneDays));
        }

        var claims = ArgumentCaptor.forClass(LaunchPromoWarning.class);
        verify(launchPromoWarningRepository, times(4)).saveAndFlush(claims.capture());
        assertEquals(List.of(15, 7, 3, 1),
                claims.getAllValues().stream().map(LaunchPromoWarning::getMilestoneDays).toList());
        verify(notificationService, times(4)).notify(any(NotificationPayload.class));
    }

    @Test
    void isSilentBeforeTheFirstMilestoneAndAfterThePromoEnds() {
        service.warnPromoHolders(PROMO_UNTIL.minusDays(16));
        service.warnPromoHolders(PROMO_UNTIL.plusDays(1));

        verifyNoInteractions(subscriptionRepository, notificationService, launchPromoWarningRepository);
    }

    @Test
    void aSkippedDayIsCaughtUpWithTheCurrentStage() {
        // The job did not run on D-7; on D-6 the D-7 stage is still owed.
        holdersToWarn(7, promoHolder(1));

        service.warnPromoHolders(PROMO_UNTIL.minusDays(6));

        verify(launchPromoWarningRepository).saveAndFlush(argThat(claim -> claim.getMilestoneDays() == 7));
        verify(notificationService).notify(any(NotificationPayload.class));
    }

    @Test
    void neverNotifiesAUserTwiceForTheSameMilestone() {
        var holder = promoHolder(1);
        holdersToWarn(3, holder);
        // Another run (or instance) already claimed this milestone for the user.
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(launchPromoWarningRepository).saveAndFlush(any(LaunchPromoWarning.class));

        service.warnPromoHolders(PROMO_UNTIL.minusDays(3));

        verify(notificationService, never()).notify(any());
    }

    @Test
    void oneUsersFailureDoesNotStopTheOthers() {
        var failing = promoHolder(1);
        var healthy = promoHolder(2);
        holdersToWarn(15, failing, healthy);
        when(notificationService.notify(argThat(payload -> payload != null && payload.user() == failing.getUser())))
                .thenThrow(new IllegalStateException("push provider down"));

        service.warnPromoHolders(PROMO_UNTIL.minusDays(15));

        verify(notificationService).notify(argThat(payload -> payload.user() == healthy.getUser()));
    }

    @Test
    void pagesThroughEveryHolderByKeyset() {
        var firstPage = List.of(promoHolder(1), promoHolder(2));
        var secondPage = List.of(promoHolder(3));
        when(subscriptionRepository.findLaunchPromoHoldersToWarn(any(), eq(15), any(), any()))
                .thenReturn(firstPage).thenReturn(secondPage).thenReturn(List.of());

        service.warnPromoHolders(PROMO_UNTIL.minusDays(15));

        verify(notificationService, times(3)).notify(any(NotificationPayload.class));
        var cursors = ArgumentCaptor.forClass(UUID.class);
        verify(subscriptionRepository, times(3)).findLaunchPromoHoldersToWarn(any(), eq(15), cursors.capture(), any());
        assertEquals(List.of(new UUID(0L, 0L), new UUID(0L, 2L), new UUID(0L, 3L)), cursors.getAllValues());
    }

    @Test
    void notificationIsATransactionalSystemNoticeWithThePromoEndDate() {
        holdersToWarn(15, promoHolder(1));

        service.warnPromoHolders(PROMO_UNTIL.minusDays(15));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals(NotificationType.SYSTEM, payload.type());
        assertEquals("notification.launch_promo_ending.title|31/12", payload.title());
        assertEquals("notification.launch_promo_ending.body|31/12", payload.body());
        assertEquals("LAUNCH_PROMO_ENDING", payload.extras().get("kind"));
        assertEquals("2026-12-31", payload.extras().get("validUntil"));
        assertEquals(15, payload.extras().get("milestoneDays"));
    }

    @Test
    void lastDaysUseTheEndingTitle() {
        holdersToWarn(1, promoHolder(1));

        service.warnPromoHolders(PROMO_UNTIL.minusDays(1));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals("notification.launch_promo_ending.last_days.title|31/12", captor.getValue().title());
    }

    @Test
    void doesNothingWhenWarningsOrThePromoAreOff() {
        properties.getSubscription().getPromo().setWarningsEnabled(false);
        service.warnPromoHolders(PROMO_UNTIL.minusDays(7));
        properties.getSubscription().getPromo().setWarningsEnabled(true);
        properties.getSubscription().getPromo().setUntil(null);
        service.warnPromoHolders(PROMO_UNTIL.minusDays(7));

        verify(subscriptionRepository, never()).findLaunchPromoHoldersToWarn(any(), anyInt(), any(), any());
    }
}
