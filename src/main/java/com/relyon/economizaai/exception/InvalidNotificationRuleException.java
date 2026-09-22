package com.relyon.economizaai.exception;

public class InvalidNotificationRuleException extends DomainException {

    public InvalidNotificationRuleException(String reason) {
        super("notificationrule.invalid", reason);
    }
}
