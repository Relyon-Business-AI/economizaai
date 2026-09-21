package com.relyon.economizai.service.subscription;

import com.relyon.economizai.dto.request.RevenueCatWebhookRequest.Event;
import com.relyon.economizai.model.RevenueEvent;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.RevenueEventRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
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
 * email), so we resolve the user from it. Events are grouped: anything that
 * grants/extends access activates PRO until the event's expiration; an
 * expiration revokes it. A bare cancellation (auto-renew turned off) is a no-op
 * — access continues until the period actually ends, then the expiry sweep (or
 * the EXPIRATION event) downgrades it.
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
    /** Events that end PRO entitlement now. */
    private static final Set<String> REVOKING = Set.of("EXPIRATION", "SUBSCRIPTION_PAUSED");

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final RevenueEventRepository revenueEventRepository;

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
        } else if (REVOKING.contains(type)) {
            subscriptionService.cancel(user);
        } else {
            // CANCELLATION / BILLING_ISSUE / TRANSFER / TEST / aliases: keep current
            // entitlement (the user still has access until the period lapses).
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
