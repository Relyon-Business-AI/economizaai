package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.AvailabilityReason;
import com.relyon.economizaai.model.enums.HomeFeature;

import java.util.List;

/**
 * Per-feature cold-start availability for the home screen, so the FE can render a
 * blur / "disponível em breve" lock — with a progress hint — instead of a bare
 * empty state on a fresh account.
 *
 * <p><b>Semantics of {@code have}/{@code need}:</b> a coarse cold-start progress,
 * not a per-item guarantee.
 * <ul>
 *   <li>Personal features: {@code have} = the household's confirmed-receipt count;
 *       {@code need} = the threshold at which that feature starts producing value.
 *       "Scan {need-have} more receipts."</li>
 *   <li>Collaborative features: {@code have} = distinct households contributing to
 *       the index (global); {@code need} = the k-anonymity minimum. Reaching it means
 *       the community is large enough to start disclosing — a specific product/market
 *       may still be below k until it too has enough contributors.</li>
 * </ul>
 * {@code available == (have >= need)}.
 */
public record HomeAvailabilityResponse(List<FeatureAvailability> features) {

    public record FeatureAvailability(
            HomeFeature feature,
            boolean available,
            AvailabilityReason reason,
            int have,
            int need) {
    }
}
