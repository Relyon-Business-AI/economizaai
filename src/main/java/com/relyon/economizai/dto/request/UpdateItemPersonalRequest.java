package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Household "personal layer" edits, allowed at ANY receipt status (including after
 * confirmation) — these never touch the immutable SEFAZ line nor the already-emitted
 * price-index observation, only how THIS household sees/accounts the line. All fields
 * optional: omit to leave unchanged.
 */
public record UpdateItemPersonalRequest(
        @Schema(description = "Optional. true = \"not mine\" (shared purchase attributed to someone else): drops the " +
                "line from the household's personal spend / consumption / savings / reports. Unlike the full-edit " +
                "`excluded` flag, the price STILL feeds the collaborative index. Omit to leave unchanged.")
        Boolean excludedFromPersonal,
        @Schema(description = "Optional. User-friendly display name shown to the household. rawDescription stays " +
                "untouched (SEFAZ audit trail). Send empty string to clear.",
                example = "Cerveja Stella 330ml")
        @Size(max = 500) String friendlyDescription,
        @Schema(description = "Optional. Unit price actually paid when this line was on promotion. When null it is " +
                "derived from paidTotalPrice ÷ quantity.")
        @DecimalMin(value = "0.0") BigDecimal paidUnitPrice,
        @Schema(description = "Optional. Total actually PAID when on promotion — must not exceed the printed " +
                "totalPrice. The printed price stays as the index baseline; this records what was really paid. " +
                "Send null to clear a previously-set manual discount.")
        @DecimalMin(value = "0.0") BigDecimal paidTotalPrice
) {}
