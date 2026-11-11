package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private NotificationDispatcher emailDispatcher;
    @Mock private NotificationDispatcher pushDispatcher;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private QuietHoursPolicy quietHours;

    /** Runs hand-offs inline, like the test-profile executor bean. */
    private Executor dispatchExecutor = Runnable::run;

    /** PRO user — delivery (push/email) is allowed. */
    private User userWithPushToken() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO)
                .pushDeviceToken("fcm-abc").build();
    }

    /** PRO user without a push token — falls back to email. */
    private User userWithoutPushToken() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO).build();
    }

    private User freeUserWithPushToken() {
        return User.builder().id(UUID.randomUUID()).email("free@e")
                .subscriptionTier(SubscriptionTier.FREE)
                .pushDeviceToken("fcm-abc").build();
    }

    private NotificationService service(boolean withEmail, boolean withPush) {
        // Default: delivery allowed (PRO). FREE-tier behavior is exercised by
        // the inbox-only test, which stubs allowsDelivery(...) false.
        lenient().when(subscriptionGate.allowsDelivery(any(), any(), anyBoolean())).thenReturn(true);
        // notify() agora salva ANTES do dispatch (pra ter o id no payload do push)
        // e de novo depois — o mock devolve a própria entidade.
        lenient().when(notificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var dispatchers = new ArrayList<NotificationDispatcher>();
        if (withEmail) {
            lenient().when(emailDispatcher.channel()).thenReturn(NotificationChannel.EMAIL);
            dispatchers.add(emailDispatcher);
        }
        if (withPush) {
            lenient().when(pushDispatcher.channel()).thenReturn(NotificationChannel.PUSH);
            dispatchers.add(pushDispatcher);
        }
        return new NotificationService(preferenceRepository, notificationRepository, subscriptionGate,
                transactionManager, dispatchers, quietHours, dispatchExecutor);
    }

    private NotificationPayload payload(User user, NotificationType type) {
        return new NotificationPayload(user, type, "Title", "Body", Map.of("k", "v"));
    }

    @Test
    void preferencesPushWhenUserHasTokenAndNoExplicitPreference() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(pushDispatcher).dispatch(any());
        verify(emailDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertEquals(NotificationChannel.PUSH, captor.getValue().getChannel());
        assertTrue(captor.getValue().isDelivered());
    }

    @Test
    void fallsBackToEmailWhenNoPushToken() {
        var user = userWithoutPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(emailDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(emailDispatcher).dispatch(any());
        verify(pushDispatcher, never()).dispatch(any());
    }

    @Test
    void respectsExplicitNonePreferenceAndDoesNotDispatch() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.NONE).build()));
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(emailDispatcher, never()).dispatch(any());
        verify(pushDispatcher, never()).dispatch(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void persistsFailureWhenDispatchFails() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.failed("no token"));
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertEquals("no token", captor.getValue().getFailureReason());
    }

    @Test
    void freeTier_persistsInboxRowButSkipsDispatch() {
        var user = freeUserWithPushToken();
        when(emailDispatcher.channel()).thenReturn(NotificationChannel.EMAIL);
        when(pushDispatcher.channel()).thenReturn(NotificationChannel.PUSH);
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(subscriptionGate.allowsDelivery(any(), eq(NotificationType.PROMO_PERSONAL), eq(false))).thenReturn(false);
        var svc = new NotificationService(preferenceRepository, notificationRepository, subscriptionGate,
                transactionManager, List.of(emailDispatcher, pushDispatcher), quietHours, dispatchExecutor);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(emailDispatcher, never()).dispatch(any());
        verify(pushDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertEquals("free_tier_inbox_only", captor.getValue().getFailureReason());
        assertEquals(NotificationChannel.PUSH, captor.getValue().getChannel());
    }

    @Test
    void notifyAfterCommit_withoutActiveTransaction_dispatchesImmediately() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notifyAfterCommit(payload(user, NotificationType.PROMO_PERSONAL));

        verify(pushDispatcher).dispatch(any());
        verify(notificationRepository, times(2)).save(any());
    }

    @Test
    void notifyAfterCommit_withActiveTransaction_dispatchesOnlyAfterCommit() {
        var user = userWithPushToken();
        var svc = service(true, true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            svc.notifyAfterCommit(payload(user, NotificationType.PROMO_PERSONAL));
            verify(pushDispatcher, never()).dispatch(any());
            verify(notificationRepository, never()).save(any());

            when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
            when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(pushDispatcher).dispatch(any());
        verify(notificationRepository, times(2)).save(any());
    }

    @Test
    void notifyAfterCommit_dispatchFailureIsSwallowed() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(pushDispatcher.dispatch(any())).thenThrow(new IllegalStateException("expo down"));
        var svc = service(true, true);

        assertDoesNotThrow(() -> svc.notifyAfterCommit(payload(user, NotificationType.PROMO_PERSONAL)));
    }

    @Test
    void persistsFailureWhenNoDispatcherForRequestedChannel() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.EMAIL).build()));
        var svc = service(false, true); // no email dispatcher (e.g., SMTP not configured)

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        // persisted before dispatch + again after (aggregate outcome) → 2 saves
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertNotNull(captor.getValue().getFailureReason());
    }

    @Test
    void multiChannel_dispatchesToBothPushAndEmail() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.INBOX).build(),
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.PUSH).build(),
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.EMAIL).build()));
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        when(emailDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(pushDispatcher).dispatch(any());
        verify(emailDispatcher).dispatch(any());
    }

    @Test
    void inboxOnlySelection_persistsRowButNoDispatch() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.INBOX).build()));
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(pushDispatcher, never()).dispatch(any());
        verify(emailDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(NotificationChannel.INBOX, captor.getValue().getChannel());
    }

    // ---- quiet hours ----

    @Test
    void quietHours_writesTheInboxRowButHoldsTheDispatchBack() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(quietHours.holdsBackNow(NotificationType.PRICE_DROP)).thenReturn(true);
        var svc = service(true, true);

        var saved = svc.notify(payload(user, NotificationType.PRICE_DROP));

        verify(pushDispatcher, never()).dispatch(any());
        verify(emailDispatcher, never()).dispatch(any());
        verify(notificationRepository).save(any());
        assertTrue(saved.isDispatchDeferred());
        assertFalse(saved.isDelivered());
        assertNull(saved.getFailureReason());
    }

    @Test
    void quietHours_exemptTypeIsDispatchedAtOnce() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(quietHours.holdsBackNow(NotificationType.RECEIPT_READY)).thenReturn(false);
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        var saved = svc.notify(payload(user, NotificationType.RECEIPT_READY));

        verify(pushDispatcher).dispatch(any());
        assertFalse(saved.isDispatchDeferred());
        assertTrue(saved.isDelivered());
    }

    @Test
    void quietHours_afterCommitPathAlsoHoldsTheDispatchBack() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(quietHours.holdsBackNow(NotificationType.PRICE_DROP)).thenReturn(true);
        var svc = service(true, true);

        svc.notifyAfterCommit(payload(user, NotificationType.PRICE_DROP));

        verify(pushDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertTrue(captor.getValue().isDispatchDeferred());
    }

    @Test
    void quietHours_freeTierRowIsNeverMarkedDeferred() {
        var user = freeUserWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        lenient().when(quietHours.holdsBackNow(any())).thenReturn(true);
        var svc = service(true, true);
        when(subscriptionGate.allows(any(), eq(Feature.PUSH_AND_EMAIL_DELIVERY))).thenReturn(false);

        var saved = svc.notify(payload(user, NotificationType.PRICE_DROP));

        assertFalse(saved.isDispatchDeferred());
        assertEquals("free_tier_inbox_only", saved.getFailureReason());
    }

    @Test
    void dispatchHeldBack_sendsWithTheNotificationIdAndClearsTheFlag() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);
        var heldBack = Notification.builder().id(UUID.randomUUID()).user(user)
                .type(NotificationType.PRICE_DROP).channel(NotificationChannel.PUSH)
                .title("Title").body("Body").payload("{\"productId\":\"abc\"}")
                .dispatchDeferred(true).build();

        svc.dispatchHeldBack(heldBack);

        var payloadCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushDispatcher).dispatch(payloadCaptor.capture());
        assertEquals("abc", payloadCaptor.getValue().extras().get("productId"));
        assertEquals(heldBack.getId().toString(), payloadCaptor.getValue().extras().get("notificationId"));
        assertEquals("Body", payloadCaptor.getValue().body());
        assertFalse(heldBack.isDispatchDeferred());
        assertTrue(heldBack.isDelivered());
        verify(notificationRepository).save(heldBack);
    }

    @Test
    void dispatchHeldBack_userMutedTheTypeOvernight_settlesWithoutSending() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of(
                NotificationPreference.builder().user(user).type(NotificationType.PRICE_DROP)
                        .channel(NotificationChannel.NONE).build()));
        var svc = service(true, true);
        var heldBack = Notification.builder().id(UUID.randomUUID()).user(user)
                .type(NotificationType.PRICE_DROP).channel(NotificationChannel.PUSH)
                .title("Title").body("Body").dispatchDeferred(true).build();

        svc.dispatchHeldBack(heldBack);

        verify(pushDispatcher, never()).dispatch(any());
        assertFalse(heldBack.isDispatchDeferred());
        verify(notificationRepository).save(heldBack);
    }

    // ---- dispatch executor ----

    @Test
    void notifyAfterCommit_runsOnTheDispatchExecutorNotOnTheCaller() {
        var user = userWithPushToken();
        var queued = new ArrayList<Runnable>();
        dispatchExecutor = queued::add;
        var svc = service(true, true);

        svc.notifyAfterCommit(payload(user, NotificationType.PROMO_PERSONAL));

        // Nothing touched the DB or the network on the calling thread.
        verify(notificationRepository, never()).save(any());
        verify(pushDispatcher, never()).dispatch(any());
        assertEquals(1, queued.size());

        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        queued.get(0).run();

        verify(pushDispatcher).dispatch(any());
        verify(notificationRepository, times(2)).save(any());
    }

    @Test
    void notifyAfterCommit_poolRejection_keepsTheInboxRowAndRecordsWhy() {
        var user = userWithPushToken();
        when(preferenceRepository.findAllByUserIdAndType(any(), any())).thenReturn(List.of());
        dispatchExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        var svc = service(true, true);

        assertDoesNotThrow(() -> svc.notifyAfterCommit(payload(user, NotificationType.PROMO_PERSONAL)));

        verify(pushDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("dispatch_pool_rejected", captor.getValue().getFailureReason());
        assertFalse(captor.getValue().isDelivered());
        assertFalse(captor.getValue().isDispatchDeferred());
    }
}
