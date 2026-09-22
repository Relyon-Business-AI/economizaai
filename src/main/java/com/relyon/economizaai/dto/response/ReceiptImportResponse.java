package com.relyon.economizaai.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a bulk chave import: the receipts queued for reconsult (poll each via
 * {@code GET /receipts/{id}}) plus every chave that was rejected up front, with a
 * localized reason so the FE never renders a raw key.
 */
public record ReceiptImportResponse(
        int received,
        int queued,
        List<UUID> queuedReceiptIds,
        int rejected,
        List<RejectedChave> rejectedChaves
) {
    /** {@code reason} is the machine key; {@code reasonMessage} its localized text. */
    public record RejectedChave(String chave, String reason, String reasonMessage) {}
}
