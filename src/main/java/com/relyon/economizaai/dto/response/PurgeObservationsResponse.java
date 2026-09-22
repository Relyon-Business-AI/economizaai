package com.relyon.economizaai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record PurgeObservationsResponse(
        @Schema(description = "How many community price observations were removed for the receipt.", example = "7")
        int removed
) {}
