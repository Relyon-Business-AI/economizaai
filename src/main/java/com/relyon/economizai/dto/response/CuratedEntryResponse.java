package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.CuratedDictionaryEntry;
import com.relyon.economizai.model.enums.ProductCategory;

import java.util.UUID;

/** A curated dictionary entry, for the admin management list. */
public record CuratedEntryResponse(
        UUID id,
        String keyword,
        String genericName,
        ProductCategory category,
        String origin
) {
    public static CuratedEntryResponse from(CuratedDictionaryEntry entry) {
        return new CuratedEntryResponse(
                entry.getId(), entry.getKeyword(), entry.getGenericName(), entry.getCategory(), entry.getOrigin());
    }
}
