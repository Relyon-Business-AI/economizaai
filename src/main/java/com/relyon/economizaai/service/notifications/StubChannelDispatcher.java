package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;

/**
 * Base for channels whose wiring exists but whose provider integration is not
 * yet implemented (Alexa, SMS, WhatsApp). It registers as a real dispatcher so
 * the channel can be selected in preferences/rules and so the orchestrator
 * writes a consistent audit row, but every dispatch records a clear
 * "not implemented" failure instead of silently dropping.
 *
 * <p>To make a channel functional, replace its subclass's {@link #deliver} with
 * the provider call (e.g. Alexa Proactive Events, Twilio/Zenvia, WhatsApp Cloud API).
 */
@Slf4j
public abstract class StubChannelDispatcher implements NotificationDispatcher {

    @Override
    public DispatchResult dispatch(NotificationPayload payload) {
        log.info("notification.{}.stub user={} type={} title='{}' (channel not yet implemented)",
                channel().name().toLowerCase(), LogMasker.email(payload.user().getEmail()),
                payload.type(), payload.title());
        return deliver(payload);
    }

    /** Hook for a real implementation. Default: record a not-implemented failure. */
    protected DispatchResult deliver(NotificationPayload payload) {
        return DispatchResult.failed(channel().name().toLowerCase() + " channel not yet implemented");
    }
}
