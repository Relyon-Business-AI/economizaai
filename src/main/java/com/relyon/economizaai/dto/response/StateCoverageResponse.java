package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.UnidadeFederativa;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Per-UF ingestion coverage: which states are VERIFIED (dedicated adapter),
 * which run on the EXPERIMENTAL fallback chain, and what real users' scans
 * have shown per chain layer. The ops view for deciding which state adapter
 * to build next.
 */
public record StateCoverageResponse(List<StateCoverageEntry> states) {

    public record StateCoverageEntry(
            UnidadeFederativa uf,
            String mode,
            long attempts,
            long successes,
            long failures,
            OffsetDateTime lastAttemptAt,
            Map<String, StrategyStats> strategies) {

        public record StrategyStats(long successes, long failures) {
        }
    }
}
