package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * A user-defined category, scoped to a household (e.g. "FRUITS"). Overlays the
 * global {@link com.relyon.economizaai.model.enums.ProductCategory} enum: the
 * household can migrate its products into it. One name per household.
 */
@Entity
@Table(name = "household_custom_categories",
        uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HouseholdCustomCategory extends BaseEntity implements HouseholdScoped {

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

    @Column(nullable = false, length = 60)
    private String name;

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
        return name;
    }
}
