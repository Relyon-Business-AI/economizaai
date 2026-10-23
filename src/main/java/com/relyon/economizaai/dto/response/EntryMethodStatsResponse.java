package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * How users are entering their notas over a window: total + a per-method breakdown with its
 * share of the total and its own confirmed/failed split. Powers the admin "input methods" panel.
 */
public record EntryMethodStatsResponse(
        int windowDays,
        long total,
        List<Line> lines) {

    public record Line(
            String method,        // ReceiptEntryMethod name (QR_SCAN, MANUAL_CHAVE, …)
            long total,
            BigDecimal sharePct,  // this method's % of all notas in the window
            long confirmed,
            long failed,
            BigDecimal failPct) { // failed / total for THIS method (how often it fails)
    }
}
