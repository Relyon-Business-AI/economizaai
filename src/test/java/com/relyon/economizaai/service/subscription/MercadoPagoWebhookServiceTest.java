package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.RevenueEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RevenueEventRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.referral.ReferralService;
import com.relyon.economizaai.service.subscription.MercadoPagoClient.AuthorizedPayment;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MercadoPagoWebhookServiceTest {

    private static final String PREAPPROVAL_EVENT = "subscription_preapproval";
    private static final String PAYMENT_EVENT = "subscription_authorized_payment";

    @Mock private MercadoPagoClient mercadoPagoClient;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private RevenueEventRepository revenueEventRepository;
    @Mock private ReferralService referralService;

    @InjectMocks private MercadoPagoWebhookService service;

    private final User user = User.builder().id(UUID.randomUUID()).email("maria@example.com").build();

    private Preapproval preapproval(String status, LocalDateTime nextPayment) {
        return new Preapproval("pre_1", status, "https://mp/init", user.getId().toString(),
                "maria@example.com", new BigDecimal("9.90"), nextPayment);
    }

    private AuthorizedPayment charge(String invoiceId, String paymentId, String paymentStatus, LocalDateTime debitDate) {
        return new AuthorizedPayment(invoiceId, "pre_1", paymentId, paymentStatus, new BigDecimal("9.90"), debitDate);
    }

    // ---------- subscription_preapproval ----------

    @Test
    void authorizedActivatesProUntilNextPaymentWithoutRecordingRevenue() {
        var nextPayment = LocalDateTime.now().plusMonths(1);
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", nextPayment));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PREAPPROVAL_EVENT, "pre_1");

        verify(subscriptionService).activatePro(user, "mercadopago", "pre_1", nextPayment);
        // Money is recorded per charge (authorized payment), never on the authorization itself.
        verifyNoInteractions(revenueEventRepository, referralService);
    }

    @Test
    void authorizedWithoutNextPaymentFallsBackToOneMonth() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PREAPPROVAL_EVENT, "pre_1");

        var captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(subscriptionService).activatePro(eq(user), eq("mercadopago"), eq("pre_1"), captor.capture());
        assertFalse(captor.getValue().isBefore(LocalDateTime.now().plusMonths(1).minusMinutes(1)));
    }

    @Test
    void cancelledKeepsAccessUntilThePaidPeriodEnds() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("cancelled", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PREAPPROVAL_EVENT, "pre_1");

        verify(subscriptionService).markNonRenewing(user, "mercadopago");
        verify(subscriptionService, never()).cancel(any());
        verifyNoInteractions(revenueEventRepository);
    }

    @Test
    void pausedKeepsAccessUntilThePaidPeriodEnds() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("paused", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PREAPPROVAL_EVENT, "pre_1");

        verify(subscriptionService).markNonRenewing(user, "mercadopago");
        verify(subscriptionService, never()).cancel(any());
    }

    @Test
    void pendingStatusIsANoOp() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("pending", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PREAPPROVAL_EVENT, "pre_1");

        verifyNoInteractions(subscriptionService);
    }

    @Test
    void unhandledTypeNeverCallsTheApi() {
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

        service.handle(PREAPPROVAL_EVENT, "pre_9");

        verifyNoInteractions(subscriptionService, revenueEventRepository);
    }

    @Test
    void apiFetchFailureIsSwallowedSoMpRetriesLater() {
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenThrow(new MercadoPagoApiException("503"));

        service.handle(PREAPPROVAL_EVENT, "pre_1");

        verifyNoInteractions(subscriptionService, revenueEventRepository);
    }

    // ---------- subscription_authorized_payment (monthly charges) ----------

    @Test
    void approvedChargeAdvancesThePeriodAndRecordsItsOwnRevenue() {
        var nextPayment = LocalDateTime.now().plusMonths(1);
        var debitDate = LocalDateTime.now().minusMinutes(3);
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_2")).thenReturn(charge("inv_2", "pay_2", "approved", debitDate));
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", nextPayment));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(revenueEventRepository.existsByProviderAndProviderRef("mercadopago", "pay_2")).thenReturn(false);

        service.handle(PAYMENT_EVENT, "inv_2");

        verify(subscriptionService).activatePro(user, "mercadopago", "pre_1", nextPayment);
        var captor = ArgumentCaptor.forClass(RevenueEvent.class);
        verify(revenueEventRepository).save(captor.capture());
        var revenue = captor.getValue();
        assertEquals("pay_2", revenue.getProviderRef(), "deduped on the payment id, not the preapproval id");
        assertEquals(new BigDecimal("9.90"), revenue.getAmount());
        assertEquals("BRL", revenue.getCurrency());
        assertEquals("PAYMENT_APPROVED", revenue.getEventType());
        assertEquals(debitDate, revenue.getOccurredAt());
        verify(referralService).rewardInviterForSubscription(user);
    }

    @Test
    void eachMonthlyChargeIsRecordedOnce() {
        var nextPayment = LocalDateTime.now().plusMonths(1);
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_2")).thenReturn(charge("inv_2", "pay_2", "approved", null));
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_3")).thenReturn(charge("inv_3", "pay_3", "approved", null));
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", nextPayment));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(revenueEventRepository.existsByProviderAndProviderRef("mercadopago", "pay_2")).thenReturn(false, true);
        when(revenueEventRepository.existsByProviderAndProviderRef("mercadopago", "pay_3")).thenReturn(false);

        service.handle(PAYMENT_EVENT, "inv_2");
        service.handle(PAYMENT_EVENT, "inv_2"); // webhook retry of the same charge
        service.handle(PAYMENT_EVENT, "inv_3"); // next month's charge

        var captor = ArgumentCaptor.forClass(RevenueEvent.class);
        verify(revenueEventRepository, times(2)).save(captor.capture());
        assertEquals("pay_2", captor.getAllValues().get(0).getProviderRef());
        assertEquals("pay_3", captor.getAllValues().get(1).getProviderRef());
    }

    @Test
    void approvedChargeFallsBackToOneMonthFromTheChargeWhenNextPaymentDidNotMoveYet() {
        var debitDate = LocalDateTime.now().minusHours(2);
        var staleNextPayment = LocalDateTime.now().minusHours(2);
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_2")).thenReturn(charge("inv_2", "pay_2", "approved", debitDate));
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("authorized", staleNextPayment));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PAYMENT_EVENT, "inv_2");

        verify(subscriptionService).activatePro(user, "mercadopago", "pre_1", debitDate.plusMonths(1));
    }

    @Test
    void chargeThatIsNotApprovedChangesNothing() {
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_2")).thenReturn(charge("inv_2", "pay_2", "rejected", null));

        service.handle(PAYMENT_EVENT, "inv_2");

        verify(mercadoPagoClient, never()).fetchPreapproval(anyString());
        verifyNoInteractions(subscriptionService, revenueEventRepository, referralService);
    }

    @Test
    void approvedChargeOfACancelledPreapprovalRecordsRevenueWithoutReactivating() {
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_2")).thenReturn(charge("inv_2", "pay_2", "approved", null));
        when(mercadoPagoClient.fetchPreapproval("pre_1")).thenReturn(preapproval("cancelled", null));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.handle(PAYMENT_EVENT, "inv_2");

        verify(subscriptionService, never()).activatePro(any(), anyString(), any(), any());
        verify(revenueEventRepository).save(any(RevenueEvent.class));
    }

    @Test
    void paymentFetchFailureIsSwallowedSoMpRetriesLater() {
        when(mercadoPagoClient.fetchAuthorizedPayment("inv_2")).thenThrow(new MercadoPagoApiException("503"));

        service.handle(PAYMENT_EVENT, "inv_2");

        verifyNoInteractions(subscriptionService, revenueEventRepository);
    }
}
