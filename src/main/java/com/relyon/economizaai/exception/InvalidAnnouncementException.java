package com.relyon.economizaai.exception;

public class InvalidAnnouncementException extends DomainException {

    private InvalidAnnouncementException(String messageKey) {
        super(messageKey);
    }

    /** The action button needs both its label and its destination, or neither. */
    public static InvalidAnnouncementException incompleteAction() {
        return new InvalidAnnouncementException("announcement.action.incomplete");
    }

    public static InvalidAnnouncementException endsBeforeItStarts() {
        return new InvalidAnnouncementException("announcement.window.invalid");
    }
}
