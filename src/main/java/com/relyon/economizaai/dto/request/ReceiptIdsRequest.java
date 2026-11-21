package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.service.ReceiptBatchLimit;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A batch of receipt ids — for bulk retry / bulk delete on the import screen. The business limit
 * of ids per request is an admin setting, checked by {@link ReceiptBatchLimit}; the annotation
 * is only the technical ceiling of a payload.
 */
public record ReceiptIdsRequest(
        @NotEmpty
        @Size(max = ReceiptBatchLimit.HARD_CEILING)
        List<UUID> ids
) {}
