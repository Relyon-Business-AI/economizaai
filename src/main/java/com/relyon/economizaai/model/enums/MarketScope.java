package com.relyon.economizaai.model.enums;

/**
 * Which merchant segments a personal spend/insights view covers. Lets the home
 * dashboard show only grocery/pharmacy (our specialty) while an "Outras notas"
 * view shows everything else — without mixing the two.
 *
 * <ul>
 *   <li>{@code ALL} — every confirmed nota (default; preserves the legacy view).</li>
 *   <li>{@code SUPPORTED} — only grocery/pharmacy (supermarket, pharmacy, food retail).</li>
 *   <li>{@code OTHER} — everything else (restaurants, pet, apparel, e-commerce, unregistered).</li>
 * </ul>
 */
public enum MarketScope {
    ALL,
    SUPPORTED,
    OTHER
}
