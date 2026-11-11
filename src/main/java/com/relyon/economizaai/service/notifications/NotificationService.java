package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Orchestrates notification delivery:
 *  1. Resolves the user's preferred channel for the given type
 *     (default: PUSH if user has a push token, else EMAIL).
 *  2. Picks the matching dispatcher (or no-ops gracefully).
 *  3. Persists a Notification row with the dispatch outcome — gives us
 *     an audit trail and a basis for a future user-facing inbox endpoint.
 *
 * <p>This is also the single gate for the quiet hours ({@link QuietHoursPolicy}):
 * inside the window the inbox row is written as usual but the outbound dispatch
 * is held back ({@code dispatchDeferred}) until {@link DeferredNotificationDrainer}
 * sends it when the window ends.
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
    private final QuietHoursPolicy quietHours;
    private final Executor dispatchExecutor;

    static final String REASON_POOL_REJECTED = "dispatch_pool_rejected";
    static final String REASON_FREE_TIER = "free_tier_inbox_only";

    public NotificationService(NotificationPreferenceRepository preferenceRepository,
                               NotificationRepository notificationRepository,
                               SubscriptionGateService subscriptionGate,
                               PlatformTransactionManager transactionManager,
                               List<NotificationDispatcher> dispatchers,
                               QuietHoursPolicy quietHours,
                               @Qualifier(AsyncConfig.NOTIFICATION_DISPATCH_EXECUTOR) Executor dispatchExecutor) {
        this.preferenceRepository = preferenceRepository;
        this.notificationRepository = notificationRepository;
        this.subscriptionGate = subscriptionGate;
        this.quietHours = quietHours;
        this.dispatchExecutor = dispatchExecutor;
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

        // Inbox is the always-on baseline (free). With enforcement on, FREE gets the inbox row
        // but no outbound dispatch for PROMOTIONAL types; transactional ones (receipt, household,
        // account notices) are always delivered. Inbox-only by choice
        // (no outbound channels selected) likewise just persists the row.
        if (outbound.isEmpty()
                || !subscriptionGate.allowsDelivery(payload.user(), payload.type(), payload.campaignId() != null)) {
            if (outbound.isEmpty()) {
                log.debug("notification.inbox_only user={} type={} reason=no_outbound_selected",
                        payload.user().getEmail(), payload.type());
            } else {
                notification.setFailureReason(REASON_FREE_TIER);
                log.info("notification.inbox_only user={} type={} reason=free_tier",
                        payload.user().getEmail(), payload.type());
            }
            return new Plan(notification, outbound, false);
        }
        if (quietHours.holdsBackNow(payload.type())) {
            notification.setDispatchDeferred(true);
            log.info("notification.deferred user={} type={} reason=quiet_hours",
                    payload.user().getEmail(), payload.type());
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
     * Without an active transaction it is handed off right away. Fire-and-forget:
     * callers that need the saved row must call {@link #notify} directly.
     *
     * <p>The work runs on the dispatch executor, never on the committing thread:
     * that thread still has its connection checked out during {@code afterCommit},
     * and an Expo/SMTP round-trip there would hold it (plus a second one for the
     * row) for seconds.
     */
    public void notifyAfterCommit(NotificationPayload payload) {
        loadRecipientForWorkerThread(payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    handOffToDispatchExecutor(payload);
                }
            });
        } else {
            handOffToDispatchExecutor(payload);
        }
    }

    /** The worker thread outlives the caller's session, so a lazy User proxy is loaded here, while it is open. */
    private void loadRecipientForWorkerThread(NotificationPayload payload) {
        try {
            Hibernate.initialize(payload.user());
        } catch (RuntimeException ex) {
            log.warn("notification.recipient_not_loaded type={} reason={}", payload.type(), ex.getMessage());
        }
    }

    /**
     * Never throws into the caller — the business result is already committed. A full
     * pool keeps the inbox row (a short write, no outbound call) and records why the
     * push did not go out.
     */
    private void handOffToDispatchExecutor(NotificationPayload payload) {
        try {
            dispatchExecutor.execute(() -> persistAndDispatch(payload));
        } catch (RejectedExecutionException rejection) {
            log.warn("notification.dispatch_rejected user={} type={} reason=pool_full",
                    payload.user().getEmail(), payload.type());
            persistInboxOnly(payload, REASON_POOL_REJECTED);
        }
    }

    private void persistInboxOnly(NotificationPayload payload, String failureReason) {
        try {
            var plan = planDelivery(payload);
            if (plan == null) return;
            var notification = plan.notification();
            // A row already held back by the quiet hours needs no worker: the drain job sends it.
            if (!notification.isDispatchDeferred() && notification.getFailureReason() == null) {
                notification.setFailureReason(failureReason);
            }
            afterCommitTxTemplate.executeWithoutResult(status -> notificationRepository.save(notification));
        } catch (RuntimeException ex) {
            log.warn("notification.inbox_only_failed user={} type={} reason={}",
                    payload.user().getEmail(), payload.type(), ex.getMessage());
        }
    }

    /**
     * Sends a notification that was held back by the quiet hours and already claimed by the
     * drain job. Channels are resolved again — the user may have muted the type or lost the
     * push token overnight. Runs untransacted; only the outcome is saved.
     */
    void dispatchHeldBack(Notification heldBack) {
        var payload = new NotificationPayload(heldBack.getUser(), heldBack.getType(), heldBack.getTitle(),
                heldBack.getBody(), deserialize(heldBack.getPayload()), heldBack.getCampaignId());
        var configured = configuredChannels(heldBack.getUser(), heldBack.getType());
        var outbound = configured.contains(NotificationChannel.NONE)
                ? EnumSet.noneOf(NotificationChannel.class)
                : outboundChannels(heldBack.getUser(), configured);
        heldBack.setDispatchDeferred(false);
        if (outbound.isEmpty()) {
            notificationRepository.save(heldBack);
            return;
        }
        if (!subscriptionGate.allowsDelivery(heldBack.getUser(), heldBack.getType(), heldBack.getCampaignId() != null)) {
            keepInboxOnly(heldBack, REASON_FREE_TIER);
            return;
        }
        dispatchAll(payload, heldBack, outbound);
        notificationRepository.save(heldBack);
    }

    /** Settles a held-back notification without sending it (it stays in the inbox). */
    void keepInboxOnly(Notification heldBack, String reason) {
        heldBack.setDispatchDeferred(false);
        heldBack.setFailureReason(reason);
        notificationRepository.save(heldBack);
    }

    /**
     * After-commit dispatch never propagates — the business result is already committed.
     * The row is persisted in its OWN transaction ({@link #afterCommitTxTemplate}) because a
     * plain {@code save()} inside the afterCommit phase does not commit; the outbound HTTP
     * dispatch then runs OUTSIDE any transaction (never pin a connection across an HTTP call).
     */
    private void persistAndDispatch(NotificationPayload payload) {
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

    private Map<String, Object> deserialize(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(extrasJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("notification.payload.deserialize_failed: {}", ex.getMessage());
            return Map.of();
        }
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
