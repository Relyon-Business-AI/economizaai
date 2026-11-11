package com.relyon.economizaai.exception;

/** An e-mail change that cannot go ahead as asked (409): each factory names the reason. */
public class EmailChangeConflictException extends DomainException {

    private EmailChangeConflictException(String messageKey) {
        super(messageKey);
    }

    /** Social-only account: there is no password to confirm the request with. */
    public static EmailChangeConflictException passwordRequired() {
        return new EmailChangeConflictException("user.email-change.password-required");
    }

    public static EmailChangeConflictException emailInUse() {
        return new EmailChangeConflictException("user.email-change.in-use");
    }

    public static EmailChangeConflictException sameEmail() {
        return new EmailChangeConflictException("user.email-change.same-email");
    }
}
