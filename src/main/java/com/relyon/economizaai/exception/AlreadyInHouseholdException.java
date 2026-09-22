package com.relyon.economizaai.exception;

public class AlreadyInHouseholdException extends DomainException {

    public AlreadyInHouseholdException() {
        super("household.already.member");
    }
}
