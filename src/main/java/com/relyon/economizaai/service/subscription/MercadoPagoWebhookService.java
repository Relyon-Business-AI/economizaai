package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.RevenueEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RevenueEventRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Translates a Mercado Pago subscription webhook into our subscription
 * lifecycle. The event only carries the preapproval id — the authoritative
 * status is re-fetched from the API (which also defeats forged ids: an
 * attacker-supplied id resolves to nothing or to its real status).
 *
 * <p>NOT {@code @Transactional}: the fetch is an outbound HTTP call; persistence
 * happens inside {@link SubscriptionService}'s own short transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoWebhookService {

    private static final String PROVIDER = "mercadopago";
    /** MP webhook types that concern the subscription lifecycle. */
    private static final Set<String> SUBSCRIPTION_TYPES = Set.of("subscription_preapproval");

    private final MercadoPagoClient mercadoPagoClient;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final RevenueEventRepository revenueEventRepository;

    public void handle(String type, String preapprovalId) {
        if (type == null || preapprovalId == null || preapprovalId.isBlank()) {
            log.warn("mercadopago.webhook ignored reason=missing_fields");
            return;
        }
        if (!SUBSCRIPTION_TYPES.contains(type.toLowerCase())) {
            log.debug("mercadopago.webhook noop type={}", type);
            return;
        }
        MercadoPagoClient.Preapproval preapproval;
        try {
            preapproval = mercadoPagoClient.fetchPreapproval(preapprovalId);
        } catch (MercadoPagoApiException apiFailure) {
            log.warn("mercadopago.webhook fetch_failed preapproval={} reason={}", preapprovalId, apiFailure.getMessage());
            return;
        }
        var userOpt = resolveUser(preapproval);
        if (userOpt.isEmpty()) {
            log.warn("mercadopago.webhook unknown_user preapproval={}", preapprovalId);
            return;
        }
        apply(userOpt.get(), preapproval);
    }

    private void apply(User user, MercadoPagoClient.Preapproval preapproval) {
        var status = preapproval.status() == null ? "" : preapproval.status().toLowerCase();
        switch (status) {
            case "authorized" -> {
                var periodEnd = preapproval.nextPaymentDate() != null
                        ? preapproval.nextPaymentDate()
                        : LocalDateTime.now().plusMonths(1);
                subscriptionService.activatePro(user, PROVIDER, preapproval.id(), periodEnd);
                recordRevenue(user, preapproval);
            }
            case "cancelled", "paused" -> subscriptionService.cancel(user);
            default -> log.debug("mercadopago.webhook noop status={} user={}",
                    status, LogMasker.email(user.getEmail()));
        }
        log.info("mercadopago.webhook applied status={} user={}", status, LogMasker.email(user.getEmail()));
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
     * One revenue row per preapproval authorization (deduped on the preapproval
     * id — MP retries and re-authorizations don't double-count).
     */
    private void recordRevenue(User user, MercadoPagoClient.Preapproval preapproval) {
        if (revenueEventRepository.existsByProviderAndProviderRef(PROVIDER, preapproval.id())) {
            return;
        }
        var amount = preapproval.transactionAmount() == null
                ? BigDecimal.ZERO
                : preapproval.transactionAmount().setScale(2, RoundingMode.HALF_UP);
        revenueEventRepository.save(RevenueEvent.builder()
                .user(user)
                .provider(PROVIDER)
                .providerRef(preapproval.id())
                .eventType("AUTHORIZED")
                .productId(null)
                .amount(amount)
                .currency("BRL")
                .occurredAt(LocalDateTime.now())
                .build());
        log.info("revenue.recorded type=AUTHORIZED amount={} currency=BRL user={}",
                amount, LogMasker.email(user.getEmail()));
    }
}
