package com.relyon.economizai.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Pipeline-health snapshot for the ops dashboard: how receipts fared over a
 * window — status mix, parse success rate, the receipts stuck long enough for the
 * sweeper to time out, per-UF outcomes, and the top failure reasons. All derived
 * from the {@code receipts} table (status + parseErrorReason + uf).
 */
public record IngestionHealthResponse(
        int windowDays,
        long totalReceipts,
        /** ReceiptStatus name → count. */
        Map<String, Long> byStatus,
        long parsedOk,
        long failedParse,
        long inFlight,
        /** parsedOk ÷ (parsedOk + failedParse), in [0,1] — in-flight excluded. */
        double successRate,
        long stuckProcessingTimeouts,
        long stuckDeviceFetchTimeouts,
        List<UfOutcomeLine> byUf,
        List<ErrorLine> topErrors) {

    /** Per-state pipeline outcome. {@code parsed} = produced items (confirmed/pending/rejected). */
    public record UfOutcomeLine(String uf, long total, long parsed, long failed, double successRate) {
    }

    /** A failure reason (the machine key before the ':') and how many receipts hit it. */
    public record ErrorLine(String reason, long count) {
    }
}
