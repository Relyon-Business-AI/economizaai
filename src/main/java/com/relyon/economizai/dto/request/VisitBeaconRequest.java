package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Anonymous top-of-funnel beacon the web landing fires once per session on first
 * load. Carries a random client id ({@code anonId}, no PII) plus the same
 * marketing attribution the FE captures for signup, so we can measure our own
 * visit→signup conversion per campaign. Public + unauthenticated.
 */
@Schema(description = "Anonymous visit beacon: random client id + landing-URL attribution.")
public record VisitBeaconRequest(
        @Schema(description = "Random per-client id stored in the browser (localStorage). No PII.")
        String anonId,
        @Schema(description = "Where the visit happened: WEB / ANDROID / IOS.", example = "WEB")
        String platform,
        AttributionInfo attribution) {
}
