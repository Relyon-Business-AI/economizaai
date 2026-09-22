package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** A batch of receipt ids — for bulk retry / bulk delete on the import screen. */
public record ReceiptIdsRequest(
        @NotEmpty
        @Size(max = 500, message = "no máximo 500 notas por operação")
        List<UUID> ids
) {}
