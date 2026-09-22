package com.relyon.economizaai.exception;

public class NotificationRuleNotFoundException extends DomainException {

    public NotificationRuleNotFoundException() {
        super("notificationrule.not.found");
    }
}
