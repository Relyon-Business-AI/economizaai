package com.relyon.economizaai.exception;

/**
 * Leaving a household you're alone in has no destination — and before this guard it
 * silently parked ALL of the user's data in a dormant household. Mapped to 400.
 */
public class SoloHouseholdLeaveException extends DomainException {

    public SoloHouseholdLeaveException() {
        super("household.leave.solo");
    }
}
