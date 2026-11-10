package com.relyon.economizaai.service.priceindex;

/**
 * Per-screen ordering of a comparison (best-markets, generic comparison):
 * <ul>
 *   <li>{@code CHEAPEST} — price ascending (the default, historical behavior).</li>
 *   <li>{@code NEAREST} — distance ascending, nulls (no geocode) last.</li>
 * </ul>
 *
 * <p>Independent of the ordering, each offer carries persistent {@code isCheapest}/
 * {@code isNearest} flags so the FE can show "Mais barato"/"Mais perto" badges no
 * matter which sort is active. No value judgment ("vale a pena?") lives here — that's
 * a deferred Phase 2 (see {@code docs/PRODUCT_COMPARISON.md}).
 */
public enum ComparisonSort {
    CHEAPEST, NEAREST
}
