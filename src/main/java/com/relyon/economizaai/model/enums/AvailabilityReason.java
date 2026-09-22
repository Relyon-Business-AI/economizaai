package com.relyon.economizaai.model.enums;

/**
 * Why a {@link HomeFeature} is (un)available — lets the FE pick the right lock
 * copy: nothing (show it), "scan more receipts" (personal), or "waiting on the
 * community to grow" (collaborative).
 */
public enum AvailabilityReason {
    /** Past cold-start — the section can be shown (data may still be sparse per item). */
    AVAILABLE,
    /** Personal gate — the household needs to scan more receipts. */
    NEEDS_MORE_RECEIPTS,
    /** Collaborative gate — more households must contribute before the index discloses. */
    NEEDS_COMMUNITY
}
