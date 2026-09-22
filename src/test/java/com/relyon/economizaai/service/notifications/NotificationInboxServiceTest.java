package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.NotificationResponse;
import com.relyon.economizaai.exception.NotificationNotFoundException;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationDestination;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationInboxServiceTest {

    @Mock private NotificationRepository repository;

    @InjectMocks private NotificationInboxService notificationInboxService;

    private User userWithId(UUID id) {
        return User.builder().id(id).email("maria@example.com").build();
    }

    private Notification notificationFor(User owner) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .type(NotificationType.PRICE_DROP)
                .channel(NotificationChannel.PUSH)
                .title("Preço caiu")
                .body("Arroz mais barato perto de você")
                .build();
    }

    @Test
    void listMapsPageToResponses() {
        var user = userWithId(UUID.randomUUID());
        var notification = notificationFor(user);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Notification> page = new PageImpl<>(List.of(notification), pageable, 1);
        when(repository.findAllByUserIdOrderByCreatedAtDesc(user.getId(), pageable)).thenReturn(page);

        Page<NotificationResponse> result = notificationInboxService.list(user, pageable);

        assertEquals(1, result.getTotalElements());
        var response = result.getContent().getFirst();
        assertEquals(notification.getId(), response.id());
        assertEquals(notification.getTitle(), response.title());
        assertEquals(NotificationType.PRICE_DROP, response.type());
        assertEquals(NotificationDestination.PRODUCT, response.destination());
    }

    @Test
    void unreadCountDelegatesToRepository() {
        var user = userWithId(UUID.randomUUID());
        when(repository.countByUserIdAndReadAtIsNull(user.getId())).thenReturn(7L);

        assertEquals(7L, notificationInboxService.unreadCount(user));
    }

    @Test
    void markReadSetsReadAtWhenUnread() {
        var user = userWithId(UUID.randomUUID());
        var notification = notificationFor(user);
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));

        notificationInboxService.markRead(user, notification.getId());

        assertNotNull(notification.getReadAt());
        verify(repository).save(notification);
    }

    @Test
    void markReadIsIdempotentWhenAlreadyRead() {
        var user = userWithId(UUID.randomUUID());
        var notification = notificationFor(user);
        var earlierReadAt = LocalDateTime.now().minusHours(2);
        notification.setReadAt(earlierReadAt);
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));

        notificationInboxService.markRead(user, notification.getId());

        assertEquals(earlierReadAt, notification.getReadAt());
        verify(repository, never()).save(any());
    }

    @Test
    void markReadThrowsWhenNotificationMissing() {
        var user = userWithId(UUID.randomUUID());
        var missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThrows(NotificationNotFoundException.class, () -> notificationInboxService.markRead(user, missingId));

        verify(repository, never()).save(any());
    }

    @Test
    void markReadThrowsWhenNotificationBelongsToAnotherUser() {
        var owner = userWithId(UUID.randomUUID());
        var otherUser = userWithId(UUID.randomUUID());
        var notification = notificationFor(owner);
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));
        var notificationId = notification.getId();

        assertThrows(NotificationNotFoundException.class,
                () -> notificationInboxService.markRead(otherUser, notificationId));

        assertNull(notification.getReadAt());
        verify(repository, never()).save(any());
    }

    @Test
    void markAllReadReturnsCountFromRepository() {
        var user = userWithId(UUID.randomUUID());
        when(repository.markAllReadForUser(eq(user.getId()), any(LocalDateTime.class))).thenReturn(4);

        assertEquals(4, notificationInboxService.markAllRead(user));
        verify(repository).markAllReadForUser(eq(user.getId()), any(LocalDateTime.class));
    }
}
