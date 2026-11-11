package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Emails the admin inbox when an account is deleted while a PAID subscription is still active.
 * Deleting the account does not cancel anything at the store / payment provider, so support
 * needs to know. Advisory only (same path and kill switch as the new-account alert): the send
 * runs on the async pool and never blocks or fails the deletion.
 */
@Slf4j
@Service
public class SubscriptionDeletionAlertService {

    private final ContactService contactService;
    private final Executor executor;
    private final boolean enabled;

    public SubscriptionDeletionAlertService(ContactService contactService,
                                            @Qualifier(AsyncConfig.RECEIPT_INGEST_EXECUTOR) Executor executor,
                                            @Value("${economizaai.signup-alert.enabled:true}") boolean enabled) {
        this.contactService = contactService;
        this.executor = executor;
        this.enabled = enabled;
    }

    public void notifyPaidSubscriberDeleted(User user, Subscription subscription) {
        if (!enabled) {
            return;
        }
        var subject = "Conta excluída com assinatura paga ativa: " + user.getEmail();
        var body = """
                Uma conta foi excluída com uma assinatura paga ainda ativa. A exclusão NÃO cancela
                a cobrança na loja / no provedor — o usuário precisa cancelar por lá.

                - Nome: %s
                - E-mail: %s
                - Provedor: %s
                - Referência no provedor: %s
                - Pago até: %s
                - Renovação já cancelada: %s
                """.formatted(user.getName(), user.getEmail(), subscription.getProvider(),
                subscription.getProviderRef() == null ? "(sem referência)" : subscription.getProviderRef(),
                subscription.getCurrentPeriodEnd() == null ? "(sem data)" : subscription.getCurrentPeriodEnd(),
                subscription.isCancelAtPeriodEnd() ? "sim" : "não");
        try {
            executor.execute(() -> contactService.notifyAdmin(subject, body));
            log.info("subscription_deletion_alert.dispatched user={}", LogMasker.email(user.getEmail()));
        } catch (RejectedExecutionException poolFull) {
            log.warn("subscription_deletion_alert.dispatch_rejected user={}", LogMasker.email(user.getEmail()));
        }
    }
}
