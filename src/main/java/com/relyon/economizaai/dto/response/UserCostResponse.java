package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.PaidApiService;

import java.util.List;

/**
 * How much a single user has cost us in paid external APIs (lifetime) — the total in cents plus a
 * breakdown by service ("de onde vem cada custo"). Attributable services only (INFOSIMPLES, captcha,
 * photo IA, SMS); batch LLM enrichment runs system-level (userId null) and never shows up here.
 */
public record UserCostResponse(long totalCents, List<ServiceLine> byService) {

    public record ServiceLine(PaidApiService service, long calls, long costCents) {
    }
}
