package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.GenericComparisonResponse;
import com.relyon.economizaai.dto.response.PromoFeedResponse;
import com.relyon.economizaai.dto.response.SponsoredPromoResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.geo.NearbyScopeService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.merchant.SponsoredPromoService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import com.relyon.economizaai.service.priceindex.GenericComparisonService;
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
    private final SponsoredPromoService sponsoredPromoService;
    private final ViewerLocationService viewerLocationService;
    private final NearbyScopeService nearbyScopeService;
    private final GenericComparisonService genericComparisonService;

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
     * Typical IN-STORE price for a product across every physical market — scoped to the
     * states the household shops in, falling back to national. The fallback for the
     * best-markets screen when no chain reaches k-anon yet. Same hybrid disclosure as the
     * other references (counts visible, price null when blocked).
     */
    @GetMapping("/products/{productId}/in-store-reference")
    public ResponseEntity<PriceIndexService.InStoreReferencePrice> inStoreReference(
            @AuthenticationPrincipal User user,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(priceIndexService.inStoreReferencePrice(productId, user.getHousehold().getId()));
    }

    /**
     * Cheapest market CHAINS for a product (one row per CNPJ root, shown as its watched
     * or closest store). Distance is measured from the user's HOME location by default;
     * pass {@code lat}/{@code lng} to measure from the current position instead (barcode
     * scan inside a store).
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
        var watched = watchedMarketService.watchedCnpjs(user);
        // Explicit position/radius (barcode scan inside a store) keeps the caller's own scope;
        // otherwise the "perto de você" cascade (explícita → estimada pelas notas → UF → nacional).
        if (useCurrentPosition || radiusKm != null) {
            var originLatitude = useCurrentPosition ? lat : user.getHomeLatitude();
            var originLongitude = useCurrentPosition ? lng : user.getHomeLongitude();
            return ResponseEntity.ok(priceIndexService.bestMarkets(productId, limit,
                    originLatitude, originLongitude, radiusKm, watched));
        }
        return ResponseEntity.ok(priceIndexService.bestMarketsForViewer(productId, limit,
                viewerLocationService.resolve(user), watched));
    }

    /**
     * "Ver todos os [tipo]" — comparação genérica do scanner: todos os produtos do mesmo
     * tipo que o escaneado ({@code generic_name_norm}, ex.: "creme de leite"), cada um com
     * seu melhor preço perto do usuário (mesma cascata + k-anon do best-markets), do mais
     * barato ao mais caro. Marcas/tamanhos diferentes: referência de faixa de preço.
     */
    @GetMapping("/products/{productId}/generic-comparison")
    public ResponseEntity<GenericComparisonResponse> genericComparison(
            @AuthenticationPrincipal User user,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(genericComparisonService.forProduct(productId, user));
    }

    /**
     * Comparação genérica por TIPO (nome), usada quando o scanner só achou a prévia do catálogo
     * (sem produto exato pra ancorar): agrupa pelos produtos do mesmo {@code generic_name_norm}.
     */
    @GetMapping("/generic-comparison")
    public ResponseEntity<GenericComparisonResponse> genericComparisonByType(
            @AuthenticationPrincipal User user,
            @RequestParam String type) {
        return ResponseEntity.ok(genericComparisonService.forType(type, user));
    }

    @GetMapping("/promos")
    public ResponseEntity<List<CommunityPromoService.CommunityPromo>> currentPromos(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(communityPromoService.detectInScope(nearbyScopeService.forUser(user, radiusKm)));
    }

    /**
     * Feed de promoções com contexto de localização para a cascata "perto de você":
     * resolve a localização efetiva (explícita → centro estimado das compras → UF) e
     * devolve-a junto da lista. O app recorta no cliente (seguidos + raio/estado +
     * "ver mais"). Separado de {@code /promos} (array legado) para não quebrar apps antigos.
     */
    @GetMapping("/promos/feed")
    public ResponseEntity<PromoFeedResponse> promosFeed(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Double radiusKm) {
        var location = viewerLocationService.resolve(user);
        var promos = communityPromoService.detectAll(
                location.latitude(), location.longitude(), radiusKm,
                watchedMarketService.watchedCnpjs(user));
        return ResponseEntity.ok(new PromoFeedResponse(location.source().name(), location.uf(), promos));
    }

    /**
     * Merchant-ANNOUNCED promos ("Patrocinado" section). Deliberately a separate
     * endpoint from /promos: announced prices are claims by paying chains, never
     * community observations, and must never mix into the organic ranking.
     * Returns [] until economizaai.merchant.promos-feed-enabled is flipped on.
     */
    @GetMapping("/sponsored-promos")
    public ResponseEntity<List<SponsoredPromoResponse>> sponsoredPromos() {
        return ResponseEntity.ok(sponsoredPromoService.currentSponsoredPromos());
    }
}
