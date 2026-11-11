package com.relyon.economizaai.model.enums;

/** How well receipt scanning is working for a state right now — shown in the in-app FAQ. */
public enum StateHealthStatus {
    /** Verified adapter, healthy. */
    WORKING,
    /** Verified adapter, but failing recently or its portal is paused. */
    UNSTABLE,
    /** Experimental: still collecting notas to validate the state. */
    VALIDATING
}
