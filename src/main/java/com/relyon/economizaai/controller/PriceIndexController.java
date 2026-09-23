package com.relyon.economizaai.controller;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Public-facing collaborative price intelligence. All endpoints respect
 * k-anonymity and minimum-sample thresholds (configured via
 * <code>economizaai.collaborative.*</code>) — they return empty results
 * rather than partial / re-identifiable data when volume is insufficient.
 *
 * <p>Distance filtering: pass {@code radiusKm} to limit markets to within
 * X km of the authenticated user's home location. Markets without a known
 * geolocation are excluded when {@code radiusKm} is set; without it,
 * {@code distanceKm} is just an extra field for the client.
 */
@RestController
@RequestMapping("/api/v1/price-index")
@RequiredArgsConstructor
@Tag(name = "Price index", description = "Anonymized collaborative price intelligence (k-anon protected)")
public class PriceIndexController {

    private final PriceIndexService priceIndexService;
    private final CommunityPromoService communityPromoService;
    private final WatchedMarketService watchedMarketService;

    @GetMapping("/products/{productId}/markets/{marketCnpj}/reference")
    public ResponseEntity<PriceIndexService.ReferencePrice> reference(@PathVariable UUID productId,
                                                                     @PathVariable String marketCnpj) {
        return ResponseEntity.ok(priceIndexService.referencePrice(productId, marketCnpj));
    }

    /**
     * National ONLINE reference price for a product — median across online sellers
     * (marketplaces + supermarket delivery), a separate series from the physical index.
     * K-anon protected: null price with counts when volume is insufficient.
     */
    @GetMapping("/products/{productId}/online-reference")
    public ResponseEntity<PriceIndexService.ReferencePrice> onlineReference(@PathVariable UUID productId) {
        return ResponseEntity.ok(priceIndexService.onlineReferencePrice(productId));
    }

    /**
     * Cheapest markets for a product. Distance is measured from the user's HOME
     * location by default; pass {@code lat}/{@code lng} to measure from the
     * current position instead (barcode scan inside a store).
     */
    @GetMapping("/products/{productId}/best-markets")
    public ResponseEntity<List<PriceIndexService.MarketPriceRow>> bestMarkets(
            @AuthenticationPrincipal User user,
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng) {
        // lat/lng are an all-or-nothing pair — using one with the home value of the
        // other would be a garbage coordinate that skews distance/radius filtering.
        var useCurrentPosition = lat != null && lng != null;
        var originLatitude = useCurrentPosition ? lat : user.getHomeLatitude();
        var originLongitude = useCurrentPosition ? lng : user.getHomeLongitude();
        return ResponseEntity.ok(priceIndexService.bestMarkets(productId, limit,
                originLatitude, originLongitude, radiusKm,
                watchedMarketService.watchedCnpjs(user)));
    }

    @GetMapping("/promos")
    public ResponseEntity<List<CommunityPromoService.CommunityPromo>> currentPromos(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(communityPromoService.detectAll(
                user.getHomeLatitude(), user.getHomeLongitude(), radiusKm,
                watchedMarketService.watchedCnpjs(user)));
    }
}
