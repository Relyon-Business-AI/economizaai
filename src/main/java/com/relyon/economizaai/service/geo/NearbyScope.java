package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The "perto de você" rule, shared by every geo-scoped feature (ofertas, digest, alertas,
 * Início, detalhe do produto, mercados, busca). A shopper in the South gets nothing from a
 * deal in the Northeast, so every feature enforces the same cascade:
 *
 * <ol>
 *   <li>watched markets always pass;</li>
 *   <li>viewer has coordinates (explicit GPS/CEP or inferred from their receipts) → only
 *       places within {@code radiusKm}. No widening: "nothing nearby" is an honest answer;</li>
 *   <li>no coordinates → only watched places (when any match), else the viewer's majority
 *       UF, else national.</li>
 * </ol>
 */
public record NearbyScope(ViewerLocation location, Set<String> watchedCnpjs, double radiusKm) {

    public enum Tier { NEARBY, WATCHED, STATE, NATIONAL }

    /** What a feature knows about one place: watched by the viewer, distance from them, its UF. */
    public record Candidate(boolean watched, Double distanceKm, String uf) {}

    public boolean isWatched(String cnpj) {
        return cnpj != null && watchedCnpjs.contains(cnpj);
    }

    /** Distance from the viewer, or null when either side has no coordinates. */
    public Double distanceKmTo(BigDecimal latitude, BigDecimal longitude) {
        if (!location.hasCoordinates() || latitude == null || longitude == null) return null;
        return DistanceCalculator.kmBetween(location.latitude(), location.longitude(), latitude, longitude);
    }

    /**
     * Single-event check (alerts): no list to fall back on, so an unlocated viewer is
     * matched by UF directly.
     */
    public boolean admits(Candidate candidate) {
        if (candidate.watched()) return true;
        if (location.hasCoordinates()) return withinRadius(candidate);
        if (location.uf() != null) return location.uf().equals(candidate.uf());
        return true;
    }

    /** List cascade: keeps the narrowest non-empty tier for an unlocated viewer. */
    public <T> List<T> narrow(List<T> items, Function<T, Candidate> toCandidate) {
        if (location.hasCoordinates()) {
            return items.stream()
                    .filter(item -> {
                        var candidate = toCandidate.apply(item);
                        return candidate.watched() || withinRadius(candidate);
                    })
                    .toList();
        }
        var watchedItems = items.stream().filter(item -> toCandidate.apply(item).watched()).toList();
        if (!watchedItems.isEmpty()) return watchedItems;
        if (location.uf() != null) {
            return items.stream().filter(item -> location.uf().equals(toCandidate.apply(item).uf())).toList();
        }
        return items;
    }

    /** Which tier {@link #narrow} applies before seeing the items (for logs). */
    public Tier tier() {
        if (location.hasCoordinates()) return Tier.NEARBY;
        if (!watchedCnpjs.isEmpty()) return Tier.WATCHED;
        return location.uf() != null ? Tier.STATE : Tier.NATIONAL;
    }

    private boolean withinRadius(Candidate candidate) {
        return candidate.distanceKm() != null && candidate.distanceKm() <= radiusKm;
    }
}
