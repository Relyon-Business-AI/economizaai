package com.relyon.economizaai.exception;

public class NotificationNotFoundException extends DomainException {

    public NotificationNotFoundException() {
        super("notification.not.found");
    }
}
