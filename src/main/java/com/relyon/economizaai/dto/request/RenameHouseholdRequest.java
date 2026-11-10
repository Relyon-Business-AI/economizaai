package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record RenameHouseholdRequest(
        @Schema(description = "Display name of the household (\"Casa da Ana\"). Blank clears it — the app then " +
                "falls back to a default built from the host's first name.", example = "Casa da Ana")
        @Size(max = 60) String name
) {}
