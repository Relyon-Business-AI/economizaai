package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.User;
import org.hibernate.Hibernate;

import java.util.UUID;

/**
 * Who scanned a receipt — the one per-member attribution a shared household needs
 * ("por Ana"). Name may be null when built from an uninitialized proxy; services fill
 * it in bulk from the ids (see {@code ReceiptService}).
 */
public record ScannedBy(UUID id, String name) {

    public static ScannedBy of(User user) {
        if (user == null) return null;
        return Hibernate.isInitialized(user)
                ? new ScannedBy(user.getId(), user.getName())
                : new ScannedBy(user.getId(), null);
    }

    public ScannedBy withName(String resolvedName) {
        return new ScannedBy(id, resolvedName);
    }
}
