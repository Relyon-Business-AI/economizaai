package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * "Caçador de descontos" leaderboard: households ranked by how many items they bought
 * below the community average. {@code entries} is the ranked list (public view = only
 * opted-in households; admin view = everyone). {@code me} is the caller's own standing
 * (null in the admin view), shown even when they haven't opted in.
 */
public record LeaderboardResponse(
        int windowDays,
        List<Entry> entries,
        Entry me) {

    /**
     * {@code handle} is a privacy-safe display name in the public view (first name), or
     * the email in the admin view. {@code rank} is the 1-based position; 0 means "not
     * ranked in this view" (e.g. the caller hasn't opted in). {@code savings} is total
     * R$ below the community average.
     */
    public record Entry(int rank, String handle, long finds, BigDecimal savings, boolean isMe) {
    }
}
