package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MergeProductRequest(
        @Schema(description = "ID of the product to be absorbed (deleted after migration). Must differ from the survivor.")
        @NotNull UUID absorbedId,

        @Schema(description = "When true, returns the migration counts without applying any change. Default false.",
                example = "false")
        Boolean dryRun
) {}
