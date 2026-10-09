package com.relyon.economizaai.exception;

public class NotificationAudienceNotFoundException extends DomainException {

    public NotificationAudienceNotFoundException(String audienceId) {
        super("audience.not.found", audienceId);
    }
}
