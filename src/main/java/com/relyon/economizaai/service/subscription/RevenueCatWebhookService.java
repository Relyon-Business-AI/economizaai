package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.dto.request.RevenueCatWebhookRequest.Event;
import com.relyon.economizaai.model.RevenueEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RevenueEventRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.referral.ReferralService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Translates a RevenueCat webhook event into our subscription lifecycle.
 *
 * <p>The app sets RevenueCat's {@code app_user_id} to our user identity (UUID or
 * email), so we resolve the user from it. Events are grouped:
 * <ul>
 *   <li>anything that grants/extends access activates PRO until the event's expiration;</li>
 *   <li>{@code CANCELLATION} (auto-renew turned off) and {@code SUBSCRIPTION_PAUSED} (sent when
 *       a pause is SCHEDULED, not when it takes effect) only mark the subscription as
 *       non-renewing — the user paid until the period end and keeps PRO until then;</li>
 *   <li>{@code EXPIRATION} ends it — back to the launch promo when the user still has it,
 *       FREE otherwise.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevenueCatWebhookService {

    private static final String PROVIDER = "revenuecat";

    /** Events that grant or extend PRO entitlement. */
    private static final Set<String> ACTIVATING = Set.of(
            "INITIAL_PURCHASE", "RENEWAL", "UNCANCELLATION", "PRODUCT_CHANGE",
            "NON_RENEWING_PURCHASE", "SUBSCRIPTION_EXTENDED", "TEMPORARY_ENTITLEMENT_GRANT");
    /** The subscription will not renew, but the paid period is still running. */
    private static final Set<String> NON_RENEWING = Set.of("CANCELLATION", "SUBSCRIPTION_PAUSED");
    private static final String EXPIRATION = "EXPIRATION";
    /** Activations that are a real purchase (not an extension/grant) — what counts as "subscribed" for referrals. */
    private static final Set<String> PURCHASES = Set.of("INITIAL_PURCHASE", "RENEWAL", "NON_RENEWING_PURCHASE");

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final RevenueEventRepository revenueEventRepository;
    private final ReferralService referralService;

    @Transactional
    public void handle(Event event) {
        if (event == null || event.type() == null || event.appUserId() == null) {
            log.warn("revenuecat.webhook ignored reason=missing_fields");
            return;
        }
        var userOpt = resolveUser(event.appUserId());
        if (userOpt.isEmpty()) {
            log.warn("revenuecat.webhook unknown_user type={} appUserId='{}'", event.type(), event.appUserId());
            return;
        }
        var user = userOpt.get();
        var type = event.type().toUpperCase();
        if (ACTIVATING.contains(type)) {
            subscriptionService.activatePro(user, PROVIDER, event.productId(), periodEnd(event));
            recordRevenue(user, event, type);
            if (isPaidPurchase(event, type)) {
                referralService.rewardInviterForSubscription(user);
            }
        } else if (NON_RENEWING.contains(type)) {
            subscriptionService.markNonRenewing(user, PROVIDER);
        } else if (EXPIRATION.equals(type)) {
            subscriptionService.endProviderSubscription(user, PROVIDER);
        } else {
            // BILLING_ISSUE / TRANSFER / TEST / aliases: keep the current entitlement (a billing
            // issue is retried by the store; the expiry sweep's grace covers the gap).
            log.debug("revenuecat.webhook noop type={} user={}", type, LogMasker.email(user.getEmail()));
            return;
        }
        log.info("revenuecat.webhook applied type={} user={}", type, LogMasker.email(user.getEmail()));
    }

    /** app_user_id is our user UUID when available, else the user's email. */
    private Optional<User> resolveUser(String appUserId) {
        try {
            return userRepository.findById(UUID.fromString(appUserId));
        } catch (IllegalArgumentException notAUuid) {
            return userRepository.findByEmail(appUserId);
        }
    }

    /** Money actually changed hands — a free trial or a store grant is not a subscription yet. */
    private static boolean isPaidPurchase(Event event, String type) {
        return PURCHASES.contains(type) && event.price() != null && event.price() > 0;
    }

    private LocalDateTime periodEnd(Event event) {
        if (event.expirationAtMs() == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(event.expirationAtMs()), ZoneOffset.UTC);
    }

    /**
     * Persists the actual money for a paid event so LTV/ROAS can become real over
     * time. Deduped on the provider's event id (webhook retries). A grant/trial
     * event with no price still records a zero-amount row (keeps the count honest).
     */
    private void recordRevenue(User user, Event event, String type) {
        if (event.id() != null && revenueEventRepository.existsByProviderAndProviderRef(PROVIDER, event.id())) {
            return;
        }
        var amount = event.price() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(event.price()).setScale(2, RoundingMode.HALF_UP);
        var revenueEvent = RevenueEvent.builder()
                .user(user)
                .provider(PROVIDER)
                .providerRef(event.id())
                .eventType(type)
                .productId(event.productId())
                .amount(amount)
                .currency(event.currency() == null ? "BRL" : event.currency())
                .occurredAt(occurredAt(event))
                .build();
        revenueEventRepository.save(revenueEvent);
        log.info("revenue.recorded type={} amount={} currency={} user={}",
                type, amount, revenueEvent.getCurrency(), LogMasker.email(user.getEmail()));
    }

    private LocalDateTime occurredAt(Event event) {
        var epochMs = event.purchasedAtMs();
        if (epochMs == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
