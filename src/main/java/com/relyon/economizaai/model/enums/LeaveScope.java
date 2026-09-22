package com.relyon.economizaai.model.enums;

/**
 * What data a leaving member takes with them (Phase 2). Anything involving ANOTHER
 * person's scans requires that person's consent (see {@link ConsentStatus}); without
 * it, a user only ever leaves with what they themselves brought/scanned.
 */
public enum LeaveScope {
    /** Only the data I originally brought in (restored by origin household). Default, no consent needed. */
    ORIGINAL_ONLY,
    /** My original + data added DURING the shared period (scanned while we were one household). */
    ORIGINAL_PLUS_SHARED,
    /** My original + a copy of the partner's data too — requires the partner's consent. */
    BOTH
}
