package com.relyon.economizaai.exception;

public class InvalidNotificationRuleException extends DomainException {

    public InvalidNotificationRuleException(String reason) {
        super("notificationrule.invalid", reason);
    }

    private InvalidNotificationRuleException(String messageKey, String[] arguments) {
        super(messageKey, arguments);
    }

    /** Fully localized variant: the whole sentence comes from the message bundle. */
    public static InvalidNotificationRuleException ofKey(String messageKey, String... arguments) {
        return new InvalidNotificationRuleException(messageKey, arguments);
    }
}
