package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A node in the two-level category taxonomy. A category with children acts as a
 * GROUP (used for dashboard roll-up); a category can also hold products directly.
 * {@code legacyEnum} is the old {@link ProductCategory} value that maps here as a
 * default target during the enum→table migration (Phase 2).
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null for a top-level group; otherwise the group this leaf belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(name = "name_pt", nullable = false, length = 60)
    private String namePt;

    @Column(name = "name_en", nullable = false, length = 60)
    private String nameEn;

    /** The old flat enum that defaults to this category (migration bridge). */
    @Enumerated(EnumType.STRING)
    @Column(name = "legacy_enum", length = 30)
    private ProductCategory legacyEnum;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;
}
