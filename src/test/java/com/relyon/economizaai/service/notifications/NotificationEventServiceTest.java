package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventServiceTest {

    @Mock private NotificationEventRepository repository;
    @Mock private ProductRepository productRepository;
    @Mock private NotificationRepository notificationRepository;

    @InjectMocks private NotificationEventService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("buyer@example.com").build();
    }

    @Test
    void record_persistsRowWithAllFieldsAndSerializedMetadata() {
        var user = user();
        var notificationId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        when(productRepository.existsById(productId)).thenReturn(true);
        when(notificationRepository.existsById(notificationId)).thenReturn(true);
        when(repository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var context = RecordContext.builder()
                .notificationId(notificationId)
                .productId(productId)
                .marketCnpj("12345678000199")
                .channel("PUSH")
                .metadata(Map.of("discountFraction", 0.22))
                .build();

        service.record(user, NotificationEventType.DEAL_TAPPED, context);

        var captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(user, saved.getUser());
        assertEquals(NotificationEventType.DEAL_TAPPED, saved.getEventType());
        assertEquals(notificationId, saved.getNotificationId());
        assertEquals(productId, saved.getProductId());
        assertEquals("12345678000199", saved.getMarketCnpj());
        assertEquals("PUSH", saved.getChannel());
        assertTrue(saved.getMetadata().contains("discountFraction"));
        assertTrue(saved.getMetadata().contains("0.22"));
    }

    @Test
    void record_withNullContext_persistsMinimalRowNoMetadata() {
        var user = user();
        when(repository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(user, NotificationEventType.SCREEN_OPENED);

        var captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(NotificationEventType.SCREEN_OPENED, saved.getEventType());
        assertNull(saved.getMetadata());
        assertNull(saved.getNotificationId());
        assertNull(saved.getProductId());
    }

    @Test
    void record_withEmptyMetadataMap_storesNull() {
        var user = user();
        when(repository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(user, NotificationEventType.DISMISSED,
                RecordContext.builder().metadata(Map.of()).build());

        var captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getMetadata());
    }

    @Test
    void record_unknownNotificationId_isNulledButEventStillRecorded() {
        // sibling of the product FK bug: a dangling notification_id would also
        // violate its FK on insert; drop it to null, keep the event.
        var user = user();
        var ghostNotification = UUID.randomUUID();
        when(notificationRepository.existsById(ghostNotification)).thenReturn(false);
        when(repository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(user, NotificationEventType.PUSH_OPENED,
                RecordContext.builder().notificationId(ghostNotification).build());

        var captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getNotificationId(), "dangling notification ref dropped, not 500");
        assertEquals(NotificationEventType.PUSH_OPENED, captor.getValue().getEventType());
    }
}
