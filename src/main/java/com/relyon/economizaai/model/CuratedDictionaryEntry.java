package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Hand-curated dictionary entry: a normalized keyword/phrase (1-3 words, no
 * accents, lowercase) mapped to a generic product name + category. Highest-
 * priority tier of {@code DictionaryClassifier}; managed at runtime via the
 * admin bulk-import endpoint (formerly seed/product-dictionary.csv).
 */
@Entity
@Table(name = "curated_dictionary_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CuratedDictionaryEntry extends BaseEntity {

    @Column(nullable = false, unique = true, length = 120)
    private String keyword;

    @Column(name = "generic_name", length = 120)
    private String genericName;

    /** Optional canonical brand this rule assigns to the matched product. */
    @Column(length = 120)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductCategory category;

    /** ADMIN (hand-curated / seeded) or LLM (written back by the enrichment layer — auditable, purgeable). */
    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private String origin = "ADMIN";
}
