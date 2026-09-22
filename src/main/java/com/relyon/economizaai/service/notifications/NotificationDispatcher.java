package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationChannel;

/**
 * One implementation per delivery channel. Returns an {@link DispatchResult}
 * describing whether delivery succeeded so {@code NotificationService} can
 * write the audit/outbox row consistently.
 */
public interface NotificationDispatcher {

    NotificationChannel channel();

    DispatchResult dispatch(NotificationPayload payload);

    record DispatchResult(boolean delivered, String failureReason) {
        public static DispatchResult ok() { return new DispatchResult(true, null); }
        public static DispatchResult failed(String reason) { return new DispatchResult(false, reason); }
    }
}
