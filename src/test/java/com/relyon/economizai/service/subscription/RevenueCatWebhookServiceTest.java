package com.relyon.economizai.service.subscription;

import com.relyon.economizai.dto.request.RevenueCatWebhookRequest.Event;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.RevenueEvent;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.RevenueEventRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueCatWebhookServiceTest {

    private static final long EXP_MS = 1893456000000L; // 2030-01-01T00:00:00Z

    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private RevenueEventRepository revenueEventRepository;
    @InjectMocks private RevenueCatWebhookService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
    }

    private Event event(String type, String appUserId, Long expMs) {
        return new Event(type, appUserId, expMs, "pro_monthly", 9.90, "BRL", null, "evt_1");
    }

    @Test
    void initialPurchase_activatesProWithPeriodEndFromMillis() {
        var user = user();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.handle(event("INITIAL_PURCHASE", "u@e", EXP_MS));

        var expectedEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(EXP_MS), ZoneOffset.UTC);
        verify(subscriptionService).activatePro(user, "revenuecat", "pro_monthly", expectedEnd);
    }

    @Test
    void renewal_activates() {
        var user = user();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.handle(event("RENEWAL", "u@e", EXP_MS));

        verify(subscriptionService).activatePro(eq(user), eq("revenuecat"), any(), any());
    }

    @Test
    void expiration_cancels() {
        var user = user();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.handle(event("EXPIRATION", "u@e", EXP_MS));

        verify(subscriptionService).cancel(user);
        verify(subscriptionService, never()).activatePro(any(), any(), any(), any());
        verify(revenueEventRepository, never()).save(any());
    }

    @Test
    void initialPurchase_recordsRealRevenue() {
        var user = user();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.handle(event("INITIAL_PURCHASE", "u@e", EXP_MS));

        var captor = ArgumentCaptor.forClass(RevenueEvent.class);
        verify(revenueEventRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(0, new BigDecimal("9.90").compareTo(saved.getAmount()));
        assertEquals("BRL", saved.getCurrency());
        assertEquals("INITIAL_PURCHASE", saved.getEventType());
        assertEquals("revenuecat", saved.getProvider());
    }

    @Test
    void duplicateEventId_isNotRecordedTwice() {
        var user = user();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));
        when(revenueEventRepository.existsByProviderAndProviderRef("revenuecat", "evt_1")).thenReturn(true);

        service.handle(event("RENEWAL", "u@e", EXP_MS));

        verify(revenueEventRepository, never()).save(any());
    }

    @Test
    void cancellation_isNoOp_keepsAccessUntilPeriodEnds() {
        var user = user();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.handle(event("CANCELLATION", "u@e", EXP_MS));

        verify(subscriptionService, never()).activatePro(any(), any(), any(), any());
        verify(subscriptionService, never()).cancel(any());
    }

    @Test
    void unknownUser_isNoOp() {
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.empty());

        service.handle(event("INITIAL_PURCHASE", "u@e", EXP_MS));

        verify(subscriptionService, never()).activatePro(any(), any(), any(), any());
    }

    @Test
    void nullEvent_isNoOp() {
        service.handle(null);
        verify(subscriptionService, never()).activatePro(any(), any(), any(), any());
        verify(subscriptionService, never()).cancel(any());
    }

    @Test
    void appUserId_asUuid_resolvesById() {
        var user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(event("INITIAL_PURCHASE", user.getId().toString(), EXP_MS));

        verify(subscriptionService).activatePro(eq(user), any(), any(), any());
    }
}
