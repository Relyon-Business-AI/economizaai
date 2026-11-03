package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.AccountDeletionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Optional churn survey sent with account deletion. Everything is nullable —
 * deletion must NEVER be blocked on giving a reason.
 */
public record DeleteAccountRequest(
        @Schema(description = "Why the user is leaving (optional).", example = "MISSING_FEATURES")
        AccountDeletionReason reason,

        @Schema(description = "Free-text detail, mainly for OTHER (optional).")
        @Size(max = 1000)
        String details
) {}
