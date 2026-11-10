package com.relyon.economizaai.exception;

/** Joining another household while still sharing one — the user must leave first. Mapped to 409. */
public class HouseholdLeaveFirstException extends DomainException {

    public HouseholdLeaveFirstException() {
        super("household.join.leave.first");
    }
}
