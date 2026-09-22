package com.relyon.economizaai.exception;

/**
 * Thrown when a client posts a notification event whose type is unknown or
 * server-only (not {@link com.relyon.economizaai.model.enums.NotificationEventType#isClientReportable()}).
 */
public class InvalidNotificationEventException extends DomainException {

    public InvalidNotificationEventException(String type) {
        super("notification.event.type.invalid", type);
    }
}
