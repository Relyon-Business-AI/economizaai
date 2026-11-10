package com.relyon.economizaai.exception;

/** Only the household host (anfitrião) may remove other members. Mapped to 403. */
public class NotHouseholdHostException extends DomainException {

    public NotHouseholdHostException() {
        super("household.not.host");
    }
}
