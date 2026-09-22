package com.relyon.economizaai.model.enums;

/**
 * Rollout mode for the relevance-feedback filter (DISMISSED/MUTED telemetry
 * suppressing deals). SHADOW is the validation stage: the filter is computed
 * and measured but never changes what the user sees — flip to ON only after
 * the relevance report shows near-zero regret (see DEV_NOTES).
 */
public enum RelevanceMode {
    /** Filter fully disabled — no signal lookup at all. */
    OFF,
    /** Compute + log what WOULD be suppressed; user-visible output unchanged. */
    SHADOW,
    /** Suppressed deals are actually removed from the deals screen and digest. */
    ON
}
