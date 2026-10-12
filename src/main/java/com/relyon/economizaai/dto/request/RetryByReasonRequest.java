package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin bulk-retry of FAILED_PARSE scans whose {@code parseErrorReason} contains
 * a fragment (case-insensitive) — e.g. {@code INFOSIMPLES} to re-queue every nota
 * that failed on the paid Infosimples call once the quota resets / circuit closes.
 */
public record RetryByReasonRequest(
        @Schema(description = "Case-insensitive fragment matched against parseErrorReason.", example = "INFOSIMPLES")
        @NotBlank @Size(max = 100) String reasonContains
) {
}
