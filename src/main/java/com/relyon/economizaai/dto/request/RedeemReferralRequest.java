package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RedeemReferralRequest(
        @Schema(description = "The referral code of the person who invited this account.", example = "ABC2345")
        @NotBlank @Size(max = 20) String code
) {}
