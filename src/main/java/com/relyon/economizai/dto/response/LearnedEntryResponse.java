package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.LearnedDictionaryEntry;
import com.relyon.economizai.model.enums.ProductCategory;

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
