package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Why users deleted their accounts over a window: total deletions, how many answered the
 * survey, a per-reason breakdown (count + % of all deletions), and the most recent free-text
 * notes. Anonymized churn — no user identity. Powers the admin "motivos de exclusão" panel.
 */
public record DeletionReasonStatsResponse(
        int windowDays,
        long total,            // all deletions in the window (answered or not)
        long answered,         // deletions that gave a reason or a note
        List<Line> lines,
        List<Note> recentNotes) {

    public record Line(
            String reason,       // AccountDeletionReason name, or "NOT_SPECIFIED" for the skipped bucket
            long count,
            BigDecimal sharePct) { // of all deletions in the window
    }

    public record Note(
            String reason,
            String details,
            OffsetDateTime createdAt) {
    }
}
