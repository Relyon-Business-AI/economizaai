package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MarketLocation;

import java.math.BigDecimal;

/**
 * One market entry as seen by the user — includes whether the user has
 * already shopped here ({@code visited}) and whether they've pinned it
 * to their watchlist ({@code watching}).
 *
 * <p>{@code name} is the global market name — the full legal razão social,
 * never modified per household. {@code friendlyName} is what the FE should
 * display: it defaults to {@code name} and is replaced by the household's
 * custom rename when set (see {@code MarketNameService}). Other households
 * are unaffected. {@code city}/{@code state} come from the geocoded
 * {@code MarketLocation} (null until geocoded).
 *
 * <p>{@code communityPurchaseCount} is how many confirmed notas the whole community scanned at
 * this store — the number on the map pin. Null when fewer than K households contributed
 * (k-anonymity), so a single household's visits are never disclosed.
 * {@code householdPurchaseCount}/{@code householdSpend} are the viewer household's own confirmed
 * notas at this store and their total (own data, exact); null when it never bought there.
 */
public record MarketResponse(
        String cnpj,
        String cnpjRoot,
        String name,
        String friendlyName,
        String address,
        String city,
        String state,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceKm,
        boolean visited,
        boolean watching,
        String chainName,
        String chainLogoUrl,
        Long communityPurchaseCount,
        Long householdPurchaseCount,
        BigDecimal householdSpend
) {
    /** A market with no disclosed community purchase count. */
    public MarketResponse(String cnpj, String cnpjRoot, String name, String friendlyName, String address,
                          String city, String state, BigDecimal latitude, BigDecimal longitude,
                          Double distanceKm, boolean visited, boolean watching,
                          String chainName, String chainLogoUrl) {
        this(cnpj, cnpjRoot, name, friendlyName, address, city, state, latitude, longitude,
                distanceKm, visited, watching, chainName, chainLogoUrl, null, null, null);
    }

    public static MarketResponse from(MarketLocation location, Double distanceKm,
                                      boolean visited, boolean watching) {
        var chain = location.getChain();
        String chainName = chain != null ? chain.getName() : null;
        String chainLogoUrl = (chain != null && chain.getLogoUrl() != null)
                ? "/chains/logo/" + chain.getId() + "?v=" + chain.getLogoUrl().hashCode()
                : null;
        return new MarketResponse(
                location.getCnpj(),
                location.getCnpjRoot(),
                location.getName(),
                location.getName(),
                location.getAddress(),
                location.getCity(),
                location.getState(),
                location.getLatitude(),
                location.getLongitude(),
                distanceKm,
                visited,
                watching,
                chainName,
                chainLogoUrl
        );
    }

    /** Copy with the household's custom display name applied. */
    public MarketResponse withFriendlyName(String friendlyName) {
        return new MarketResponse(cnpj, cnpjRoot, name, friendlyName, address, city, state,
                latitude, longitude, distanceKm, visited, watching, chainName, chainLogoUrl,
                communityPurchaseCount, householdPurchaseCount, householdSpend);
    }

    /** Copy with the community purchase count set (null keeps it undisclosed). */
    public MarketResponse withCommunityPurchaseCount(Long communityPurchaseCount) {
        return new MarketResponse(cnpj, cnpjRoot, name, friendlyName, address, city, state,
                latitude, longitude, distanceKm, visited, watching, chainName, chainLogoUrl,
                communityPurchaseCount, householdPurchaseCount, householdSpend);
    }

    /** Copy with the viewer household's own purchases at this store. */
    public MarketResponse withHouseholdPurchases(Long householdPurchaseCount, BigDecimal householdSpend) {
        return new MarketResponse(cnpj, cnpjRoot, name, friendlyName, address, city, state,
                latitude, longitude, distanceKm, visited, watching, chainName, chainLogoUrl,
                communityPurchaseCount, householdPurchaseCount, householdSpend);
    }
}
