package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * Result of running a watch on demand: the products that hit its criteria right now
 * and whether the alert webhook was actually dispatched (false = no webhook configured,
 * no new hits, or dispatch failed — the log line has the reason).
 */
public record GarimpoWatchRunResponse(
        GarimpoWatchResponse watch,
        List<GarimpoProductResponse> hits,
        boolean webhookNotified) {
}
