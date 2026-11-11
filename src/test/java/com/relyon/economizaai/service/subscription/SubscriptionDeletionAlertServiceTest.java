package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.service.ContactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SubscriptionDeletionAlertServiceTest {

    private final ContactService contactService = mock(ContactService.class);
    private final Executor sameThread = Runnable::run;

    private final User user = User.builder().id(UUID.randomUUID()).name("Maria").email("test31877@economizaai.app").build();
    private final Subscription subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
            .provider("revenuecat").providerRef("economizaai_premium_monthly")
            .currentPeriodEnd(LocalDateTime.of(2027, 2, 10, 12, 0)).build();

    @Test
    void emailsTheAdminWithProviderAndPaidUntil() {
        var service = new SubscriptionDeletionAlertService(contactService, sameThread, true);

        service.notifyPaidSubscriberDeleted(user, subscription);

        var body = ArgumentCaptor.forClass(String.class);
        verify(contactService).notifyAdmin(
                eq("Conta excluída com assinatura paga ativa: test31877@economizaai.app"), body.capture());
        assertTrue(body.getValue().contains("revenuecat"));
        assertTrue(body.getValue().contains("economizaai_premium_monthly"));
        assertTrue(body.getValue().contains("2027-02-10"));
    }

    @Test
    void killSwitchSilencesTheAlert() {
        var service = new SubscriptionDeletionAlertService(contactService, sameThread, false);

        service.notifyPaidSubscriberDeleted(user, subscription);

        verifyNoInteractions(contactService);
    }

    @Test
    void aFullPoolNeverFailsTheDeletion() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("pool full");
        };
        var service = new SubscriptionDeletionAlertService(contactService, rejecting, true);

        assertDoesNotThrow(() -> service.notifyPaidSubscriberDeleted(user, subscription));
        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }
}
