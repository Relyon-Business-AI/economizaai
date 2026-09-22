package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private NotificationDispatcher emailDispatcher;
    @Mock private NotificationDispatcher pushDispatcher;

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
        // the inbox-only test, which stubs allows(...) false.
        lenient().when(subscriptionGate.allows(any(), eq(Feature.PUSH_AND_EMAIL_DELIVERY))).thenReturn(true);
        var dispatchers = new ArrayList<NotificationDispatcher>();
        if (withEmail) {
            lenient().when(emailDispatcher.channel()).thenReturn(NotificationChannel.EMAIL);
            dispatchers.add(emailDispatcher);
        }
        if (withPush) {
            lenient().when(pushDispatcher.channel()).thenReturn(NotificationChannel.PUSH);
            dispatchers.add(pushDispatcher);
        }
        return new NotificationService(preferenceRepository, notificationRepository, subscriptionGate, dispatchers);
    }

    private NotificationPayload payload(User user, NotificationType type) {
        return new NotificationPayload(user, type, "Title", "Body", Map.of("k", "v"));
    }

    @Test
    void preferencesPushWhenUserHasTokenAndNoExplicitPreference() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(pushDispatcher).dispatch(any());
        verify(emailDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(NotificationChannel.PUSH, captor.getValue().getChannel());
        assertTrue(captor.getValue().isDelivered());
    }

    @Test
    void fallsBackToEmailWhenNoPushToken() {
        var user = userWithoutPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(emailDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(emailDispatcher).dispatch(any());
        verify(pushDispatcher, never()).dispatch(any());
    }

    @Test
    void respectsExplicitNonePreferenceAndDoesNotDispatch() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.of(
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
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.failed("no token"));
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertEquals("no token", captor.getValue().getFailureReason());
    }

    @Test
    void freeTier_persistsInboxRowButSkipsDispatch() {
        var user = freeUserWithPushToken();
        when(emailDispatcher.channel()).thenReturn(NotificationChannel.EMAIL);
        when(pushDispatcher.channel()).thenReturn(NotificationChannel.PUSH);
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(subscriptionGate.allows(any(), eq(Feature.PUSH_AND_EMAIL_DELIVERY))).thenReturn(false);
        var svc = new NotificationService(preferenceRepository, notificationRepository, subscriptionGate,
                List.of(emailDispatcher, pushDispatcher));

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
    void persistsFailureWhenNoDispatcherForRequestedChannel() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.EMAIL).build()));
        var svc = service(false, true); // no email dispatcher (e.g., SMTP not configured)

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertNotNull(captor.getValue().getFailureReason());
    }
}
