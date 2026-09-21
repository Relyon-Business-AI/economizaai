package com.relyon.economizai.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * Weekly cohort retention: group users by the week they signed up, then track how
 * many of that same group were active (scanned a receipt) 0, 1, 2 … weeks later.
 * It answers "do the people who join keep coming back, or do they try it once and
 * leave?" — the classic retention triangle plus a per-channel pooled curve so the
 * owner can see which acquisition source brings users that stick.
 *
 * <p>Week 0 is the signup week itself (activation). Counts are distinct users, and
 * a user belongs to exactly one channel, so pooling across channels never double
 * counts. {@code weeks} is the number of week-offset columns the grid spans.
 */
public record RetentionCohortResponse(
        int weeks,
        List<CohortWeek> cohorts,
        List<ChannelCurve> byChannel) {

    /**
     * One signup-week row of the triangle. {@code activeByWeek[k]} = distinct users
     * from this cohort active k weeks after signup (index 0 = signup week). The list
     * only spans the weeks this cohort is old enough to have observed, so the FE
     * renders a triangle (recent cohorts are shorter), never fabricated future zeros.
     */
    public record CohortWeek(LocalDate weekStart, long size, List<Long> activeByWeek) {
    }

    /**
     * Pooled retention curve for one acquisition channel: {@code retentionByWeek[k]}
     * in [0,1] is the share of that channel's users still active k weeks in, pooled
     * across every cohort old enough to have observed week k (so a fresh cohort never
     * drags the later weeks down). {@code users} is the channel's total cohort size.
     */
    public record ChannelCurve(String channel, long users, List<Double> retentionByWeek) {
    }
}
