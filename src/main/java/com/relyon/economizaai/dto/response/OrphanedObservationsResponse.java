package com.relyon.economizaai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrphanedObservationsResponse(
        @Schema(description = "Count of price observations with no audit row (leftovers from deleted accounts).", example = "0")
        long orphaned
) {}
