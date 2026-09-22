package com.relyon.economizaai.exception;

/**
 * Implemented by experimental-chain exceptions that carry what the unknown
 * portal actually returned (captcha type, sitekey, HTTP status, body snippet).
 * This evidence flows into {@code state_ingestion_attempts} and the admin
 * alert — it's the raw material for implementing the state's dedicated adapter.
 */
public interface ExperimentalPortalEvidence {

    /** Human-readable evidence block (already CPF-sanitized and truncated). */
    String portalEvidence();
}
