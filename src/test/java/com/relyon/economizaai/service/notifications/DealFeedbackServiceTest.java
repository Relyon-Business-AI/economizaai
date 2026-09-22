package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.repository.NotificationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealFeedbackServiceTest {

    @Mock private NotificationEventRepository eventRepository;

    private DealFeedbackService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private static final String MARKET_CNPJ = "12345678000199";

    @BeforeEach
    void setUp() {
        service = new DealFeedbackService(eventRepository);
        ReflectionTestUtils.setField(service, "mode", RelevanceMode.SHADOW);
        ReflectionTestUtils.setField(service, "dismissedDays", 14);
        ReflectionTestUtils.setField(service, "mutedDays", 180);
    }

    private User user() {
        return User.builder().id(userId).email("u@e").build();
    }

    private NotificationEvent signal(NotificationEventType type, String marketCnpj, int daysAgo) {
        return NotificationEvent.builder()
                .id(UUID.randomUUID())
                .eventType(type)
                .productId(productId)
                .marketCnpj(marketCnpj)
                .occurredAt(OffsetDateTime.now().minusDays(daysAgo))
                .build();
    }

    @Test
    void offMode_returnsEmptyWithoutQuerying() {
        ReflectionTestUtils.setField(service, "mode", RelevanceMode.OFF);

        var suppressions = service.suppressionsFor(user());

        assertTrue(suppressions.isEmpty());
        verify(eventRepository, never()).findFeedbackSignals(any(), any());
    }

    @Test
    void mutedProduct_isSuppressedAtEveryMarket() {
        when(eventRepository.findFeedbackSignals(any(), any()))
                .thenReturn(List.of(signal(NotificationEventType.MUTED, null, 30)));

        var suppressions = service.suppressionsFor(user());

        assertTrue(suppressions.suppresses(productId, MARKET_CNPJ));
        assertTrue(suppressions.suppresses(productId, "99999999000100"));
        assertTrue(suppressions.suppressesProduct(productId));
    }

    @Test
    void dismissedWithMarket_suppressesOnlyThatPair() {
        when(eventRepository.findFeedbackSignals(any(), any()))
                .thenReturn(List.of(signal(NotificationEventType.DISMISSED, MARKET_CNPJ, 2)));

        var suppressions = service.suppressionsFor(user());

        assertTrue(suppressions.suppresses(productId, MARKET_CNPJ));
        assertFalse(suppressions.suppresses(productId, "99999999000100"));
        assertFalse(suppressions.suppressesProduct(productId));
    }

    @Test
    void dismissedWithoutMarket_suppressesTheProductEverywhere() {
        when(eventRepository.findFeedbackSignals(any(), any()))
                .thenReturn(List.of(signal(NotificationEventType.DISMISSED, null, 2)));

        var suppressions = service.suppressionsFor(user());

        assertTrue(suppressions.suppresses(productId, MARKET_CNPJ));
        assertTrue(suppressions.suppressesProduct(productId));
    }

    @Test
    void dismissalOlderThanItsWindow_expires() {
        // 20 days ago > 14-day dismissal window (but inside the 180-day fetch horizon)
        when(eventRepository.findFeedbackSignals(any(), any()))
                .thenReturn(List.of(signal(NotificationEventType.DISMISSED, MARKET_CNPJ, 20)));

        var suppressions = service.suppressionsFor(user());

        assertFalse(suppressions.suppresses(productId, MARKET_CNPJ));
        assertTrue(suppressions.isEmpty());
    }

    @Test
    void muteOutlivesADismissalOfTheSameAge() {
        var otherProduct = UUID.randomUUID();
        var oldMute = signal(NotificationEventType.MUTED, null, 60);
        var oldDismiss = NotificationEvent.builder()
                .id(UUID.randomUUID())
                .eventType(NotificationEventType.DISMISSED)
                .productId(otherProduct)
                .occurredAt(OffsetDateTime.now().minusDays(60))
                .build();
        when(eventRepository.findFeedbackSignals(any(), any()))
                .thenReturn(List.of(oldMute, oldDismiss));

        var suppressions = service.suppressionsFor(user());

        assertTrue(suppressions.suppressesProduct(productId), "60-day-old mute still active (180d window)");
        assertFalse(suppressions.suppressesProduct(otherProduct), "60-day-old dismissal expired (14d window)");
    }

    @Test
    void noSignals_returnsEmpty() {
        when(eventRepository.findFeedbackSignals(any(), any())).thenReturn(List.of());

        assertTrue(service.suppressionsFor(user()).isEmpty());
    }

    @Test
    void emptySuppressionSet_neverSuppresses() {
        var empty = DealFeedbackService.SuppressionSet.empty();
        assertFalse(empty.suppresses(productId, MARKET_CNPJ));
        assertEquals(true, empty.isEmpty());
    }
}
