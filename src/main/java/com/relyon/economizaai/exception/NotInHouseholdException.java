package com.relyon.economizaai.exception;

public class NotInHouseholdException extends DomainException {

    public NotInHouseholdException() {
        super("household.not.member");
    }
}
