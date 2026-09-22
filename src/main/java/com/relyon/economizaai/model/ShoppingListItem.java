package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row on a shopping list. Either a canonical Product (auto-suggestions
 * + optimizer-friendly) or a free-text user-typed entry — never both, but
 * one is required.
 */
@Entity
@Table(name = "shopping_list_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ShoppingListItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shopping_list_id", nullable = false)
    private ShoppingList shoppingList;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "free_text", length = 255)
    private String freeText;

    @Column(precision = 12, scale = 3, nullable = false)
    @lombok.Builder.Default
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int position = 0;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean checked = false;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;
}
