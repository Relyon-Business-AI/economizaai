package com.relyon.economizaai.exception;

public class HouseholdNotFoundException extends DomainException {

    public HouseholdNotFoundException() {
        super("household.not.found");
    }
}
