package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates notification delivery:
 *  1. Resolves the user's preferred channel for the given type
 *     (default: PUSH if user has a push token, else EMAIL).
 *  2. Picks the matching dispatcher (or no-ops gracefully).
 *  3. Persists a Notification row with the dispatch outcome — gives us
 *     an audit trail and a basis for a future user-facing inbox endpoint.
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final SubscriptionGateService subscriptionGate;
    private final Map<NotificationChannel, NotificationDispatcher> dispatchersByChannel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * Persists the notification row in a BRAND-NEW transaction. Needed only on the
     * {@link #notifyAfterCommit} path: a {@code save()} executed inside the
     * {@code afterCommit} phase of another transaction does not commit on its own
     * (the original tx is already completing and there is no active tx to join),
     * so every receipt-lifecycle notification was silently lost. REQUIRES_NEW
     * suspends the completing tx and commits the row in its own.
     */
    private final TransactionTemplate afterCommitTxTemplate;

    public NotificationService(NotificationPreferenceRepository preferenceRepository,
                               NotificationRepository notificationRepository,
                               SubscriptionGateService subscriptionGate,
                               PlatformTransactionManager transactionManager,
                               List<NotificationDispatcher> dispatchers) {
        this.preferenceRepository = preferenceRepository;
        this.notificationRepository = notificationRepository;
        this.subscriptionGate = subscriptionGate;
        this.afterCommitTxTemplate = new TransactionTemplate(transactionManager);
        this.afterCommitTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dispatchersByChannel = new EnumMap<>(NotificationChannel.class);
        for (var dispatcher : dispatchers) this.dispatchersByChannel.put(dispatcher.channel(), dispatcher);
        log.info("Notification dispatchers active: {}", this.dispatchersByChannel.keySet());
    }

    /**
     * Persist + dispatch one notification. Returns the saved {@link Notification}
     * (with id) so callers can attach telemetry to it, or {@code null} when the
     * user has opted out of this type (NONE channel) and nothing was created.
     *
     * <p>Deliberately NOT {@code @Transactional}: dispatch is an outbound HTTP
     * call (SMTP/Expo/Twilio, multi-second timeouts) and a transaction spanning
     * it would pin a DB connection for the duration. The only write is the
     * single save at the end, which runs in the repository's own short
     * transaction.
     */
    public Notification notify(NotificationPayload payload) {
        var plan = planDelivery(payload);
        if (plan == null) return null;
        // Persist BEFORE dispatch so the push payload carries the notification id —
        // the app reports PUSH_OPENED with it on tap (open attribution).
        var saved = notificationRepository.save(plan.notification());
        if (plan.dispatch()) {
            dispatchAll(payload, saved, plan.outbound());
            saved = notificationRepository.save(saved);
        }
        return saved;
    }

    /** Resolve channels and build the (unsaved) row for a payload, or null when muted. */
    private Plan planDelivery(NotificationPayload payload) {
        var configured = configuredChannels(payload.user(), payload.type());
        if (configured.contains(NotificationChannel.NONE)) {
            log.debug("notification.skipped user={} type={} reason=muted",
                    payload.user().getEmail(), payload.type());
            return null;
        }
        var outbound = outboundChannels(payload.user(), configured);
        var primary = outbound.stream().findFirst().orElse(NotificationChannel.INBOX);

        var notification = Notification.builder()
                .user(payload.user())
                .type(payload.type())
                .channel(primary)
                .title(payload.title())
                .body(payload.body())
                .payload(serialize(payload.extras()))
                .campaignId(payload.campaignId())
                .delivered(false)
                .build();

        // Inbox is the always-on baseline (free). FREE tier gets the inbox row but no
        // outbound dispatch; only push/email delivery is PRO. Inbox-only by choice
        // (no outbound channels selected) likewise just persists the row.
        if (outbound.isEmpty()
                || !subscriptionGate.allows(payload.user(), Feature.PUSH_AND_EMAIL_DELIVERY)) {
            if (outbound.isEmpty()) {
                log.debug("notification.inbox_only user={} type={} reason=no_outbound_selected",
                        payload.user().getEmail(), payload.type());
            } else {
                notification.setFailureReason("free_tier_inbox_only");
                log.info("notification.inbox_only user={} type={} reason=free_tier",
                        payload.user().getEmail(), payload.type());
            }
            return new Plan(notification, outbound, false);
        }
        return new Plan(notification, outbound, true);
    }

    private record Plan(Notification notification, Set<NotificationChannel> outbound, boolean dispatch) {}

    /** Fan out to every selected outbound channel; the row records aggregate delivery. */
    private void dispatchAll(NotificationPayload payload, Notification saved, Set<NotificationChannel> outbound) {
        var anyDelivered = false;
        String lastFailure = null;
        for (var channel : outbound) {
            var dispatcher = dispatchersByChannel.get(channel);
            if (dispatcher == null) {
                lastFailure = "no dispatcher registered for channel " + channel;
                log.warn("notification.no_dispatcher user={} type={} channel={}",
                        payload.user().getEmail(), payload.type(), channel);
                continue;
            }
            var result = dispatcher.dispatch(withNotificationId(payload, saved));
            anyDelivered = anyDelivered || result.delivered();
            if (!result.delivered()) lastFailure = result.failureReason();
        }
        saved.setDelivered(anyDelivered);
        saved.setDeliveredAt(anyDelivered ? LocalDateTime.now() : null);
        saved.setFailureReason(anyDelivered ? null : lastFailure);
    }

    /** Dispatch copy whose extras include the persisted notification id (tap attribution). */
    private NotificationPayload withNotificationId(NotificationPayload payload, Notification saved) {
        if (saved.getId() == null) return payload;
        var extras = new HashMap<String, Object>(payload.extras() != null ? payload.extras() : Map.of());
        extras.put("notificationId", saved.getId().toString());
        return new NotificationPayload(payload.user(), payload.type(), payload.title(),
                payload.body(), extras, payload.campaignId());
    }

    /**
     * Defer {@link #notify} to AFTER the surrounding transaction commits, so the
     * outbound dispatch (SMTP/Expo/Twilio) never runs inside a DB transaction —
     * and a rolled-back business operation never notifies anyone. Callers build
     * the payload inside their transaction (the COLLECT half); the dispatch and
     * the Notification row land after commit in their own short transactions.
     * Without an active transaction it dispatches immediately. Fire-and-forget:
     * callers that need the saved row must call {@link #notify} directly.
     */
    public void notifyAfterCommit(NotificationPayload payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchDeferred(payload);
                }
            });
        } else {
            dispatchDeferred(payload);
        }
    }

    /**
     * After-commit dispatch never propagates — the business result is already committed.
     * The row is persisted in its OWN transaction ({@link #afterCommitTxTemplate}) because a
     * plain {@code save()} inside the afterCommit phase does not commit; the outbound HTTP
     * dispatch then runs OUTSIDE any transaction (never pin a connection across an HTTP call).
     */
    private void dispatchDeferred(NotificationPayload payload) {
        try {
            var plan = planDelivery(payload);
            if (plan == null) return;
            var saved = afterCommitTxTemplate.execute(status -> notificationRepository.save(plan.notification()));
            if (plan.dispatch() && saved != null) {
                dispatchAll(payload, saved, plan.outbound());
                afterCommitTxTemplate.executeWithoutResult(status -> notificationRepository.save(saved));
            }
        } catch (RuntimeException ex) {
            log.warn("notification.after_commit_failed user={} type={} reason={}",
                    payload.user().getEmail(), payload.type(), ex.getMessage());
        }
    }

    /** The raw set of channels the user configured for this type (empty = never configured). */
    private Set<NotificationChannel> configuredChannels(User user, NotificationType type) {
        var configured = EnumSet.noneOf(NotificationChannel.class);
        preferenceRepository.findAllByUserIdAndType(user.getId(), type)
                .forEach(preference -> configured.add(preference.getChannel()));
        return configured;
    }

    /**
     * The outbound channels to dispatch to (inbox is implicit/always). When the user never
     * configured this type, default to push if they have a token, else email. Only PUSH/EMAIL
     * are honored here — WhatsApp lives in the digest flow, not the per-type matrix.
     */
    private Set<NotificationChannel> outboundChannels(User user, Set<NotificationChannel> configured) {
        if (configured.isEmpty()) {
            return EnumSet.of(hasPushToken(user) ? NotificationChannel.PUSH : NotificationChannel.EMAIL);
        }
        var outbound = EnumSet.copyOf(configured);
        outbound.retainAll(EnumSet.of(NotificationChannel.PUSH, NotificationChannel.EMAIL));
        return outbound;
    }

    private static boolean hasPushToken(User user) {
        return user.getPushDeviceToken() != null && !user.getPushDeviceToken().isBlank();
    }

    private String serialize(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(extras);
        } catch (Exception ex) {
            log.warn("notification.payload.serialize_failed: {}", ex.getMessage());
            return null;
        }
    }
}
