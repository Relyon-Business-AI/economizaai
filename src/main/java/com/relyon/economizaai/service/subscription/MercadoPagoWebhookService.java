package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.RevenueEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RevenueEventRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.referral.ReferralService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates Mercado Pago subscription webhooks into our subscription lifecycle. Two event
 * types matter:
 * <ul>
 *   <li>{@code subscription_preapproval} — the subscription itself changed (authorized,
 *       cancelled, paused);</li>
 *   <li>{@code subscription_authorized_payment} — one recurring charge. Each APPROVED charge
 *       advances the paid period and is recorded as its own revenue event (deduped on the
 *       payment id), so every month counts, not only the first.</li>
 * </ul>
 * The event only carries an id — the authoritative record is always re-fetched from the API
 * (which also defeats forged ids: an attacker-supplied id resolves to nothing or to its real
 * status).
 *
 * <p>A cancelled or paused preapproval stops FUTURE charges; the period already paid is
 * honoured — the subscription is only marked non-renewing and the expiry sweep downgrades
 * when it lapses.
 *
 * <p>NOT {@code @Transactional}: the fetch is an outbound HTTP call; persistence
 * happens inside {@link SubscriptionService}'s own short transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoWebhookService {

    private static final String PROVIDER = "mercadopago";
    private static final String PREAPPROVAL_TYPE = "subscription_preapproval";
    private static final String AUTHORIZED_PAYMENT_TYPE = "subscription_authorized_payment";
    private static final String APPROVED = "approved";
    private static final String AUTHORIZED = "authorized";

    private final MercadoPagoClient mercadoPagoClient;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final RevenueEventRepository revenueEventRepository;
    private final ReferralService referralService;

    public void handle(String type, String resourceId) {
        if (type == null || resourceId == null || resourceId.isBlank()) {
            log.warn("mercadopago.webhook ignored reason=missing_fields");
            return;
        }
        try {
            switch (type.toLowerCase()) {
                case PREAPPROVAL_TYPE -> handlePreapproval(resourceId);
                case AUTHORIZED_PAYMENT_TYPE -> handleAuthorizedPayment(resourceId);
                default -> log.debug("mercadopago.webhook noop type={}", type);
            }
        } catch (MercadoPagoApiException apiFailure) {
            log.warn("mercadopago.webhook fetch_failed type={} id={} reason={}",
                    type, resourceId, apiFailure.getMessage());
        }
    }

    private void handlePreapproval(String preapprovalId) {
        var preapproval = mercadoPagoClient.fetchPreapproval(preapprovalId);
        var user = resolveUser(preapproval).orElse(null);
        if (user == null) {
            log.warn("mercadopago.webhook unknown_user preapproval={}", preapprovalId);
            return;
        }
        var status = preapproval.status() == null ? "" : preapproval.status().toLowerCase();
        switch (status) {
            case AUTHORIZED -> subscriptionService.activatePro(
                    user, PROVIDER, preapproval.id(), paidUntil(preapproval, LocalDateTime.now()));
            case "cancelled", "paused" -> subscriptionService.markNonRenewing(user, PROVIDER);
            default -> {
                log.debug("mercadopago.webhook noop status={} user={}", status, LogMasker.email(user.getEmail()));
                return;
            }
        }
        log.info("mercadopago.webhook applied status={} user={}", status, LogMasker.email(user.getEmail()));
    }

    /** A recurring charge: only an APPROVED payment advances the period and counts as revenue. */
    private void handleAuthorizedPayment(String authorizedPaymentId) {
        var payment = mercadoPagoClient.fetchAuthorizedPayment(authorizedPaymentId);
        if (!APPROVED.equalsIgnoreCase(payment.paymentStatus())) {
            log.info("mercadopago.webhook payment_not_approved authorizedPayment={} paymentStatus={}",
                    authorizedPaymentId, payment.paymentStatus());
            return;
        }
        if (payment.preapprovalId() == null || payment.preapprovalId().isBlank()) {
            log.warn("mercadopago.webhook payment_without_preapproval authorizedPayment={}", authorizedPaymentId);
            return;
        }
        var preapproval = mercadoPagoClient.fetchPreapproval(payment.preapprovalId());
        var user = resolveUser(preapproval).orElse(null);
        if (user == null) {
            log.warn("mercadopago.webhook unknown_user preapproval={}", preapproval.id());
            return;
        }
        if (AUTHORIZED.equalsIgnoreCase(preapproval.status())) {
            var chargedAt = payment.debitDate() != null ? payment.debitDate() : LocalDateTime.now();
            subscriptionService.activatePro(user, PROVIDER, preapproval.id(), paidUntil(preapproval, chargedAt));
        }
        var newlyRecorded = recordPaymentRevenue(user, payment);
        if (newlyRecorded) {
            referralService.rewardInviterForSubscription(user);
        }
        log.info("mercadopago.webhook payment_applied user={} preapprovalStatus={}",
                LogMasker.email(user.getEmail()), preapproval.status());
    }

    /**
     * The next charge date is the end of the period just paid. When Mercado Pago has not moved
     * it forward yet (or sent none), fall back to one month from the charge.
     */
    private static LocalDateTime paidUntil(MercadoPagoClient.Preapproval preapproval, LocalDateTime chargedAt) {
        var nextPayment = preapproval.nextPaymentDate();
        if (nextPayment != null && nextPayment.isAfter(LocalDateTime.now().plusDays(1))) {
            return nextPayment;
        }
        return chargedAt.plusMonths(1);
    }

    /** external_reference carries our user UUID; payer_email is the fallback. */
    private Optional<User> resolveUser(MercadoPagoClient.Preapproval preapproval) {
        if (preapproval.externalReference() != null && !preapproval.externalReference().isBlank()) {
            try {
                var byId = userRepository.findById(UUID.fromString(preapproval.externalReference()));
                if (byId.isPresent()) return byId;
            } catch (IllegalArgumentException notAUuid) {
                // fall through to email
            }
        }
        return preapproval.payerEmail() == null
                ? Optional.empty()
                : userRepository.findByEmail(preapproval.payerEmail());
    }

    /**
     * One revenue row per approved charge, deduped on the payment id (webhook retries don't
     * double-count).
     *
     * @return false when this payment had already been recorded
     */
    private boolean recordPaymentRevenue(User user, MercadoPagoClient.AuthorizedPayment payment) {
        var paymentRef = payment.paymentId() != null ? payment.paymentId() : payment.id();
        if (revenueEventRepository.existsByProviderAndProviderRef(PROVIDER, paymentRef)) {
            return false;
        }
        var amount = payment.transactionAmount() == null
                ? BigDecimal.ZERO
                : payment.transactionAmount().setScale(2, RoundingMode.HALF_UP);
        revenueEventRepository.save(RevenueEvent.builder()
                .user(user)
                .provider(PROVIDER)
                .providerRef(paymentRef)
                .eventType("PAYMENT_APPROVED")
                .productId(payment.preapprovalId())
                .amount(amount)
                .currency("BRL")
                .occurredAt(payment.debitDate() != null ? payment.debitDate() : LocalDateTime.now())
                .build());
        log.info("revenue.recorded type=PAYMENT_APPROVED amount={} currency=BRL user={}",
                amount, LogMasker.email(user.getEmail()));
        return true;
    }
}
