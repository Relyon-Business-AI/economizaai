package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushOptInNudgeSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private LocalizedMessageService messageService;
    @InjectMocks private PushOptInNudgeScheduler scheduler;

    @BeforeEach
    void stubTexts() {
        lenient().when(messageService.translate(eq("notification.push_optin.title"), any(Locale.class)))
                .thenReturn("titulo");
        lenient().when(messageService.translate(eq("notification.push_optin.body"), any(Locale.class)))
                .thenReturn("corpo");
    }

    private User userWithoutToken() {
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test18273@economizaai.app");
        user.setLocale("pt");
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Notification>> savedNudges() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void createsInboxOnlyNudgeWithEnablePushDestination() {
        var user = userWithoutToken();
        when(userRepository.findPushOptInNudgeCandidates(any(), any(), any()))
                .thenReturn(List.of(user)).thenReturn(List.of());

        scheduler.nudgeUsersWithoutPushToken();

        var nudgesCaptor = savedNudges();
        verify(notificationRepository).saveAll(nudgesCaptor.capture());
        assertEquals(1, nudgesCaptor.getValue().size());
        var saved = nudgesCaptor.getValue().get(0);
        assertEquals(user, saved.getUser());
        assertEquals(NotificationType.PUSH_OPTIN, saved.getType());
        assertEquals(NotificationChannel.INBOX, saved.getChannel());
        assertTrue(saved.getPayload().contains("ENABLE_PUSH"));
    }

    @Test
    void recentlyNudgedUsersAreFilteredByTheQuery_nothingToSave() {
        when(userRepository.findPushOptInNudgeCandidates(any(), any(), any())).thenReturn(List.of());

        scheduler.nudgeUsersWithoutPushToken();

        verify(notificationRepository, never()).saveAll(anyList());
        // No unbounded load and no per-user lookups.
        verify(userRepository, never()).findByPushDeviceTokenIsNull();
        verify(notificationRepository, never())
                .existsByUserIdAndPayloadContainingAndCreatedAtAfter(any(), any(), any());
    }

    @Test
    void walksEveryPage_oneSaveAllPerPage_usingTheLastIdAsCursor() {
        var firstPageUser = userWithoutToken();
        var secondPageUser = userWithoutToken();
        when(userRepository.findPushOptInNudgeCandidates(any(), any(), any()))
                .thenReturn(List.of(firstPageUser))
                .thenReturn(List.of(secondPageUser))
                .thenReturn(List.of());

        scheduler.nudgeUsersWithoutPushToken();

        verify(notificationRepository, times(2)).saveAll(anyList());
        verify(userRepository).findPushOptInNudgeCandidates(any(), eq(firstPageUser.getId()), any());
        verify(userRepository).findPushOptInNudgeCandidates(any(), eq(secondPageUser.getId()), any());
    }

    @Test
    void aFailedPageDoesNotStopTheNextOne() {
        var firstPageUser = userWithoutToken();
        var secondPageUser = userWithoutToken();
        when(userRepository.findPushOptInNudgeCandidates(any(), any(), any()))
                .thenReturn(List.of(firstPageUser))
                .thenReturn(List.of(secondPageUser))
                .thenReturn(List.of());
        when(notificationRepository.saveAll(anyList()))
                .thenThrow(new IllegalStateException("db hiccup"))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> scheduler.nudgeUsersWithoutPushToken());

        verify(notificationRepository, times(2)).saveAll(anyList());
    }
}
