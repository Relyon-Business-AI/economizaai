package com.relyon.economizaai.exception;

public class InvalidInviteCodeException extends DomainException {

    public InvalidInviteCodeException(String inviteCode) {
        super("household.invite.invalid", inviteCode);
    }
}
