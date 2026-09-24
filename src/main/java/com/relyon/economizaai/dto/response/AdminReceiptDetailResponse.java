package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.User;

import java.util.UUID;

/**
 * Admin-facing detail of a single receipt: the full {@link ReceiptResponse}
 * (items, QR payload, localized parse-error message) plus the owning user —
 * the extra context an admin needs when triaging a failed nota that a regular
 * (household-scoped) detail view can't show.
 */
public record AdminReceiptDetailResponse(
        ReceiptResponse receipt,
        Owner owner
) {
    public record Owner(UUID id, String name, String email) {}

    public static AdminReceiptDetailResponse of(ReceiptResponse receipt, User owner) {
        var ownerView = owner == null ? null : new Owner(owner.getId(), owner.getName(), owner.getEmail());
        return new AdminReceiptDetailResponse(receipt, ownerView);
    }
}
