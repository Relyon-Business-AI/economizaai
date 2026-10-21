package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushOptInNudgeSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private LocalizedMessageService messageService;
    @InjectMocks private PushOptInNudgeScheduler scheduler;

    private User userWithoutToken() {
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test18273@economizaai.app");
        user.setLocale("pt");
        return user;
    }

    @Test
    void createsInboxOnlyNudgeWithEnablePushDestination() {
        var user = userWithoutToken();
        when(userRepository.findByPushDeviceTokenIsNull()).thenReturn(List.of(user));
        when(notificationRepository.existsByUserIdAndPayloadContainingAndCreatedAtAfter(
                eq(user.getId()), anyString(), any(LocalDateTime.class))).thenReturn(false);
        when(messageService.translate(eq("notification.push_optin.title"), any(Locale.class))).thenReturn("titulo");
        when(messageService.translate(eq("notification.push_optin.body"), any(Locale.class))).thenReturn("corpo");

        scheduler.nudgeUsersWithoutPushToken();

        var notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notificationCaptor.capture());
        var saved = notificationCaptor.getValue();
        assertEquals(NotificationType.PUSH_OPTIN, saved.getType());
        assertEquals(NotificationChannel.INBOX, saved.getChannel());
        assertTrue(saved.getPayload().contains("ENABLE_PUSH"));
    }

    @Test
    void skipsUsersNudgedRecently() {
        var user = userWithoutToken();
        when(userRepository.findByPushDeviceTokenIsNull()).thenReturn(List.of(user));
        when(notificationRepository.existsByUserIdAndPayloadContainingAndCreatedAtAfter(
                eq(user.getId()), anyString(), any(LocalDateTime.class))).thenReturn(true);

        scheduler.nudgeUsersWithoutPushToken();

        verify(notificationRepository, never()).save(any());
    }
}
