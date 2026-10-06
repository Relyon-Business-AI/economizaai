package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotNull;

public record SetMerchantPromoActiveRequest(
        @NotNull Boolean active
) {}
