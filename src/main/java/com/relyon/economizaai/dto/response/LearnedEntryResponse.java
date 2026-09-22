package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.time.LocalDateTime;
import java.util.UUID;

/** A learned (auto-promoted) dictionary entry, for the admin management list. */
public record LearnedEntryResponse(
        UUID id,
        String normalizedToken,
        String genericName,
        ProductCategory category,
        int sampleCount,
        LocalDateTime promotedAt
) {
    public static LearnedEntryResponse from(LearnedDictionaryEntry entry) {
        return new LearnedEntryResponse(
                entry.getId(), entry.getNormalizedToken(), entry.getGenericName(),
                entry.getCategory(), entry.getSampleCount(), entry.getPromotedAt());
    }
}
