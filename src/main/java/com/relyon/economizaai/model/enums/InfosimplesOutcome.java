package com.relyon.economizaai.model.enums;

/**
 * How the paid Infosimples lookup already answered for a nota during the current ingest chain.
 * Absent (null) means it has not been tried. Carried from the server attempt to the later
 * device-fetch steps so the same paid question is not asked again.
 */
public enum InfosimplesOutcome {
    /** The provider answered: it has no usable data for this nota — final, asking again cannot help. */
    NO_DATA,
    /** The call failed for a transient reason (provider or portal unstable). */
    FAILED
}
