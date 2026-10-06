package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.Size;

public record RejectMerchantClaimRequest(
        @Size(max = 500) String reason
) {}
