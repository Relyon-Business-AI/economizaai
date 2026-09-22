package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MarketLocation;

import java.math.BigDecimal;

/**
 * One market entry as seen by the user — includes whether the user has
 * already shopped here ({@code visited}) and whether they've pinned it
 * to their watchlist ({@code watching}).
 *
 * <p>{@code name} is the global market name (never modified per household).
 * {@code friendlyName} is what the FE should display: it defaults to
 * {@code name} and is replaced by the household's custom rename when set
 * (see {@code MarketNameService}). Other households are unaffected.
 */
public record MarketResponse(
        String cnpj,
        String cnpjRoot,
        String name,
        String friendlyName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceKm,
        boolean visited,
        boolean watching
) {
    public static MarketResponse from(MarketLocation location, Double distanceKm,
                                      boolean visited, boolean watching) {
        return new MarketResponse(
                location.getCnpj(),
                location.getCnpjRoot(),
                location.getName(),
                location.getName(),
                location.getAddress(),
                location.getLatitude(),
                location.getLongitude(),
                distanceKm,
                visited,
                watching
        );
    }

    /** Copy with the household's custom display name applied. */
    public MarketResponse withFriendlyName(String friendlyName) {
        return new MarketResponse(cnpj, cnpjRoot, name, friendlyName, address,
                latitude, longitude, distanceKm, visited, watching);
    }
}
