package com.relyon.economizaai.model.enums;

/**
 * How often a user wants the deals-digest rollup push (Phase C).
 * {@link #OFF} is the user's master switch — when OFF we never send a digest,
 * regardless of newsworthy deals.
 */
public enum DigestFrequency {
    DAILY,
    WEEKLY,
    OFF
}
