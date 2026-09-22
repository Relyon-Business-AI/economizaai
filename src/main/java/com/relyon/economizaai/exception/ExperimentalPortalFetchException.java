package com.relyon.economizaai.exception;

/**
 * An experimental state's portal fetch failed with something a dedicated
 * adapter builder needs to see (4xx status + error body, or the transient
 * failure pattern after retries). Extends {@link SefazFetchException} so the
 * chain's rescue/retry semantics are unchanged.
 */
public class ExperimentalPortalFetchException extends SefazFetchException
        implements ExperimentalPortalEvidence {

    private final String evidence;

    public ExperimentalPortalFetchException(String state, String evidence) {
        super(state);
        this.evidence = evidence;
    }

    @Override
    public String portalEvidence() {
        return evidence;
    }
}
