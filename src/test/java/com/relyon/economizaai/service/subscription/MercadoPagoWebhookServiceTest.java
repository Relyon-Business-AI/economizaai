package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.RevenueEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RevenueEventRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.subscription.MercadoPagoClient.Preapproval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MercadoPagoWebhookServiceTest {

    @Mock private MercadoPagoClient mercadoPagoClient;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private RevenueEventRepository revenueEventRepository;

    @InjectMocks private MercadoPagoWebhookService service;

    private final User user = User.builder().id(UUID.randomUUID()).email("maria@example.com").build();

    private Preapproval preapproval(String status, LocalDateTime nextPayment) {
        return new Preapproval("pre_1", status, "https://mp/init", user.getId().toString(),
                "maria@example.com", new BigDecimal("9.90"), nextPayment);
    }

    @Test
    void authorizedActivatesProUntilNextPaymentAndRecordsRevenue() {
        var nextPayment = LocalDateTime.now().plusMonths(1);
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", nextPayment));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(revenueEventRepository.existsByProviderAndProviderRef("mercadopago", "pre_1")).thenReturn(false);

        service.handle("subscription_preapproval", "pre_1");

        verify(subscriptionService).activatePro(user, "mercadopago", "pre_1", nextPayment);
        var captor = ArgumentCaptor.forClass(RevenueEvent.class);
        verify(revenueEventRepository).save(captor.capture());
        assertEquals(new BigDecimal("9.90"), captor.getValue().getAmount());
        assertEquals("BRL", captor.getValue().getCurrency());
    }

    @Test
    void authorizedWithoutNextPaymentFallsBackToOneMonth() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(revenueEventRepository.existsByProviderAndProviderRef(anyString(), anyString())).thenReturn(true);

        service.handle("subscription_preapproval", "pre_1");

        var captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(subscriptionService).activatePro(eq(user), eq("mercadopago"), eq("pre_1"), captor.capture());
        var lowerBound = LocalDateTime.now().plusMonths(1).minusMinutes(1);
        assertEquals(false, captor.getValue().isBefore(lowerBound));
        // dedup: no second revenue row
        verify(revenueEventRepository, never()).save(any());
    }

    @Test
    void cancelledDropsUserToFree() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("cancelled", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle("subscription_preapproval", "pre_1");

        verify(subscriptionService).cancel(user);
        verifyNoInteractions(revenueEventRepository);
    }

    @Test
    void pendingStatusIsANoOp() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("pending", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle("subscription_preapproval", "pre_1");

        verify(subscriptionService, never()).activatePro(any(), anyString(), any(), any());
        verify(subscriptionService, never()).cancel(any());
    }

    @Test
    void nonSubscriptionTypeNeverCallsTheApi() {
        service.handle("payment", "12345");

        verifyNoInteractions(mercadoPagoClient, subscriptionService, revenueEventRepository);
    }

    @Test
    void unknownUserIsALoggedNoOp() {
        var orphan = new Preapproval("pre_9", "authorized", null, UUID.randomUUID().toString(),
                "ghost@example.com", null, null);
        when(mercadoPagoClient.fetchPreapproval("pre_9")).thenReturn(orphan);
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        service.handle("subscription_preapproval", "pre_9");

        verifyNoInteractions(subscriptionService, revenueEventRepository);
    }

    @Test
    void apiFetchFailureIsSwallowedSoMpRetriesLater() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenThrow(new MercadoPagoApiException("503"));

        service.handle("subscription_preapproval", "pre_1");

        verifyNoInteractions(subscriptionService, revenueEventRepository);
    }
}
