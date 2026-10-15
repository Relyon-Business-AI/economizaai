package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/**
 * A household's manual category correction for a product — "evidence, not truth".
 * It overrides the category that household sees (the global {@link Product} stays
 * as-is, so other households are unaffected), and each row is a vote a later
 * consensus pass can use to refine the global default.
 *
 * <p>Mirrors {@link HouseholdProductAlias} (per-household friendly name) but for
 * category. One row per (household, product).
 */
@Entity
@Table(name = "household_product_category_overrides",
        uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HouseholdProductCategoryOverride extends BaseEntity implements HouseholdScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    // The household this row ORIGINALLY belonged to. Set once at creation, never
    // rewritten on merge (household_id is the current location). Lets a split
    // restore each person's data to where it came from. See HouseholdMergeService.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_household_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Household originHousehold;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Target global category — null when the override targets a custom category. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProductCategory category;

    /** Target custom category — null when the override targets a global enum. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_category_id")
    private HouseholdCustomCategory customCategory;

    /**
     * Target new-taxonomy node (categories.id) — null unless the household corrected
     * the item into a fine leaf/group. Exactly one of category/customCategory/categoryNodeId
     * is set. Its display NAME isn't on the entity (needs the categories table), so the
     * service resolves the label; the entity only owns the discriminated key.
     */
    @Column(name = "category_node_id")
    private UUID categoryNodeId;

    /**
     * The category label this household sees from the ENTITY ALONE: custom name or
     * enum name. Null for a taxonomy-node override — the service resolves that via
     * the categories table (see HouseholdProductCategoryOverrideService).
     */
    public String effectiveLabel() {
        if (customCategory != null) return customCategory.getName();
        return category != null ? category.name() : null;
    }

    /**
     * A discriminated bucket key that never collides across the custom/enum/node spaces:
     * {@code "custom:<id>"}, {@code "node:<id>"} or {@code "enum:<name>"}. Two different
     * categories sharing a display label therefore stay in separate buckets.
     */
    public String effectiveKey() {
        if (customCategory != null) return "custom:" + customCategory.getId();
        if (categoryNodeId != null) return "node:" + categoryNodeId;
        return category != null ? "enum:" + category.name() : null;
    }

    // Default origin to the current household on first persist, so callers never
    // have to remember to set it. Merge code overrides household_id later but leaves
    // originHousehold untouched.
    @PrePersist
    private void defaultOriginHousehold() {
        if (originHousehold == null) {
            originHousehold = household;
        }
    }

    @Override
    public String collisionKey() {
        return product != null ? product.getId().toString() : null;
    }
}

