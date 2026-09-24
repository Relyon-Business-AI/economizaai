package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.util.UUID;

/** A curated dictionary entry, for the admin management list. */
public record CuratedEntryResponse(
        UUID id,
        String keyword,
        String genericName,
        String brand,
        ProductCategory category,
        String origin
) {
    public static CuratedEntryResponse from(CuratedDictionaryEntry entry) {
        return new CuratedEntryResponse(
                entry.getId(), entry.getKeyword(), entry.getGenericName(), entry.getBrand(),
                entry.getCategory(), entry.getOrigin());
    }
}
