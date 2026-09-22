package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.MergeCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record JoinHouseholdRequest(
        @Schema(description = "6-character invite code from another household. Case-insensitive. " +
                "Codes expire 48h after generation; use POST /households/me/invite-code/regenerate to rotate.",
                example = "ABC123")
        @NotBlank @Size(min = 6, max = 8) String inviteCode,

        @Schema(description = "Whether to bring your existing data into the household you're joining. " +
                "If false/omitted, only your membership moves; your data stays on your original household " +
                "and can be restored if you later leave. (Requires the merge feature to be enabled.)",
                example = "false")
        Boolean bringData,

        @Schema(description = "Which categories of data to merge when bringData=true. Omit or empty = merge " +
                "ALL categories. On a conflict (same item already in the target household), the target's " +
                "value is kept and yours is parked, restorable on split.",
                example = "[\"RECEIPTS\",\"SHOPPING_LISTS\"]")
        Set<MergeCategory> mergeCategories
) {}
