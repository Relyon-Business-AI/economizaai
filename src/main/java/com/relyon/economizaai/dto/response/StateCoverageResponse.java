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

    /**
     * {@code attempts}/{@code successes}/{@code failures} count NOTAS (receipts created in the
     * window) by final outcome — whichever chain layer resolved them (portal, on-device fetch,
     * Infosimples, LLM). {@code pending} = still in flight (processing, waiting for the device or
     * the portal/contingency resync). {@code failureReasons} = machine key → notas.
     * {@code strategies} is the per-LAYER telemetry (a layer can fail and the next one rescue the
     * same nota, so its failures are NOT nota failures).
     */
    public record StateCoverageEntry(
            UnidadeFederativa uf,
            String mode,
            long attempts,
            long successes,
            long failures,
            long pending,
            long users,
            OffsetDateTime lastAttemptAt,
            Map<String, Long> failureReasons,
            Map<String, StrategyStats> strategies) {

        public record StrategyStats(long successes, long failures) {
        }
    }
}
