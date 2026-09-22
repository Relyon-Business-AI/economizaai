package com.relyon.economizaai.model.enums;

/**
 * Result of one experimental-state ingestion layer attempt. EXHAUSTED marks the
 * terminal row written when every layer failed for a receipt — the row that
 * carries the admin notification dedup flag.
 */
public enum StateIngestionOutcome {
    SUCCESS,
    FETCH_FAILED,
    PARSE_FAILED,
    EXHAUSTED
}
