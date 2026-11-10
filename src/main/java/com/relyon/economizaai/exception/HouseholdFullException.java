package com.relyon.economizaai.exception;

import lombok.Getter;

/**
 * Raised when joining a household would exceed its member cap. {@code upgradeAvailable}
 * is true when the cap hit is the FREE one (a PRO member would lift it) — mapped to
 * 402 so the app can offer Premium; the absolute PRO cap maps to a plain 400.
 */
@Getter
public class HouseholdFullException extends DomainException {

    private final boolean upgradeAvailable;

    public HouseholdFullException(int limit, boolean upgradeAvailable) {
        super(upgradeAvailable ? "household.full.free" : "household.full.max", String.valueOf(limit));
        this.upgradeAvailable = upgradeAvailable;
    }
}
