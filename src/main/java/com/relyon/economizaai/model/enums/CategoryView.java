package com.relyon.economizaai.model.enums;

/**
 * Which "category lens" an analytics/listing request should use.
 *
 * <ul>
 *   <li>{@link #HOUSEHOLD} (default) — each product belongs to exactly ONE
 *       effective category for this household: its per-household override (a
 *       custom category name or a corrected enum) when present, else the global
 *       {@code Product.category}. No product is double-counted.</li>
 *   <li>{@link #GLOBAL} — ignore household overrides; filter/group purely by the
 *       global {@code Product.category}. This is the pre-lens behavior.</li>
 * </ul>
 */
public enum CategoryView {
    HOUSEHOLD,
    GLOBAL
}
