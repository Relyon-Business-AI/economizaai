package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.Notification;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNotificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private NotificationRepository notificationRepository;

    @InjectMocks private AdminNotificationService service;

    @Test
    void throwsWhenEmailNotFound() {
        when(userRepository.findByEmail("missing@e")).thenReturn(Optional.empty());
        var request = new SendTestNotificationRequest("missing@e", null, null, null);
        assertThrows(UserNotFoundException.class, () -> service.sendTest(request));
    }

    @Test
    void dispatchesWithProvidedFieldsAndDefaults() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").build();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.sendTest(new SendTestNotificationRequest("u@e", "Custom", null, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals(user, payload.user());
        assertEquals("Custom", payload.title());
        assertNotNull(payload.body());
        assertEquals(NotificationType.PROMO_PERSONAL, payload.type());
    }

    @Test
    void fallsBackToSystemTypeWhenOmitted() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").build();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.sendTest(new SendTestNotificationRequest("u@e", null, null, null));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.SYSTEM, captor.getValue().type());
    }

    @Test
    void listSentMapsNotificationsWithRecipientEmail() {
        var user = User.builder().id(UUID.randomUUID()).email("dest@economizaai.app").build();
        var notification = Notification.builder()
                .user(user).type(NotificationType.PROMO_PERSONAL).channel(NotificationChannel.PUSH)
                .title("Arroz caiu de preço").body("R$ 20 → R$ 17").delivered(true)
                .deliveredAt(LocalDateTime.now()).build();
        notification.setCreatedAt(LocalDateTime.now());
        when(notificationRepository.findSentSince(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notification)));

        var page = service.listSent(30, PageRequest.of(0, 25));

        assertEquals(1, page.getTotalElements());
        var row = page.getContent().get(0);
        assertEquals("dest@economizaai.app", row.userEmail());
        assertEquals("Arroz caiu de preço", row.title());
        assertEquals(NotificationType.PROMO_PERSONAL, row.type());
    }

    @Test
    void listSentClampsWindowToAtLeastOneDay() {
        when(notificationRepository.findSentSince(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listSent(0, PageRequest.of(0, 25));

        verify(notificationRepository).findSentSince(any(), eq(PageRequest.of(0, 25)));
    }
}
