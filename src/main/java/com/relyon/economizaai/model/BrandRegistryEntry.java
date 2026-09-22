package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Brand-registry entry: a normalized key (abbreviation or full name as it
 * appears in NFC-e text) mapped to the canonical display name. Multiple keys
 * may share one display name. Managed at runtime via the admin bulk-import
 * endpoint (formerly seed/brand-registry.csv).
 */
@Entity
@Table(name = "brand_registry_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BrandRegistryEntry extends BaseEntity {

    @Column(name = "normalized_key", nullable = false, unique = true, length = 120)
    private String normalizedKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /** CURATED (hand-maintained / seed) or DERIVED (from the EAN catalog). */
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String source = "CURATED";
}
