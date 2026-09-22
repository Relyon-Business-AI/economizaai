package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record ConfirmReceiptRequest(
        @Schema(
                description = "Optional. IDs of items to mark as excluded before confirming. " +
                        "Useful when the household shopped together with someone outside it and only " +
                        "some items belong to them. Excluded items stay on the receipt for audit but " +
                        "don't count toward spend, consumption, or the price index. Omit / send empty " +
                        "to keep all items.",
                example = "[\"a1b2c3d4-...\", \"e5f6g7h8-...\"]"
        )
        List<UUID> excludedItemIds
) {}
