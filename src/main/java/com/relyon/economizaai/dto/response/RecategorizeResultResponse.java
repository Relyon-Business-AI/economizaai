package com.relyon.economizaai.dto.response;

/**
 * Outcome of applying a catalog re-categorization. Only products whose freshly
 * extracted category differs from the stored one AND aren't manually locked
 * (source=USER) are updated; products with no suggestion (extractor returns
 * null category) are left untouched.
 */
public record RecategorizeResultResponse(
        long totalProducts,
        int updated,
        int skippedUserOverrides,
        int skippedMl,        // ML suggestions left untouched (default; unless includeMl=true)
        int unchanged
) {}
