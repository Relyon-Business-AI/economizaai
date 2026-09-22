package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateContributionRequest(
        @Schema(description = "true: future receipts feed the anonymized collaborative price index. " +
                "false: receipts stay in your personal history only. LGPD opt-out toggle.")
        @NotNull Boolean contributionOptIn
) {}
