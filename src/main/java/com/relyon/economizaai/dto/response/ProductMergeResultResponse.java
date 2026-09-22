package com.relyon.economizaai.dto.response;

import java.util.UUID;

/**
 * Summary of what a merge operation moved (or would move, in dry-run mode).
 * Counts per affected table — useful for sanity-checking before a curator
 * commits, and for the audit log after.
 */
public record ProductMergeResultResponse(
        UUID survivorId,
        UUID absorbedId,
        boolean dryRun,
        boolean applied,
        long aliasesMigrated,
        long receiptItemsRepointed,
        long priceObservationsMigrated,
        long manualPurchasesRepointed,
        long shoppingListItemsRepointed,
        long householdAliasesMigrated,
        long householdAliasesDroppedAsConflict,
        long consumptionSnoozesMigrated,
        long consumptionSnoozesDroppedAsConflict
) {}
