package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SetMarketNameRequest;
import com.relyon.economizaai.dto.response.MarketProductPriceResponse;
import com.relyon.economizaai.dto.response.MarketResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.geo.WatchedMarketService.MarketScope;
import com.relyon.economizaai.service.priceindex.MarketPricesService;
import com.relyon.economizaai.service.priceindex.MarketPricesSort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Markets the user has shopped at, plus user-curated "watchlist" of CNPJs
 * they want monitored beyond the home-radius default — e.g. a market on
 * the way to work.
 */
@RestController
@RequestMapping("/api/v1/markets")
@RequiredArgsConstructor
@Tag(name = "Markets", description = "Market catalogue + user-curated watchlist")
public class MarketController {

    private final WatchedMarketService watchedMarketService;
    private final MarketNameService marketNameService;
    private final MarketPricesService marketPricesService;

    /**
     * "Mercados" screen. {@code scope=ALL} (default) returns markets within 20 km of the
     * viewer's resolved origin UNION the household's watched markets; {@code scope=MINE}
     * returns only the watched ones. Sorted by {@code distanceKm} ascending (nulls last),
     * so a market from a receipt scanned far away never dominates the top. Pass
     * {@code lat}/{@code lng} (all-or-nothing) to measure from the device's current position.
     */
    @GetMapping
    public ResponseEntity<List<MarketResponse>> list(@AuthenticationPrincipal User user,
                                                     @RequestParam(defaultValue = "ALL") MarketScope scope,
                                                     @RequestParam(required = false) BigDecimal lat,
                                                     @RequestParam(required = false) BigDecimal lng) {
        return ResponseEntity.ok(watchedMarketService.listMarkets(user, scope, lat, lng));
    }

    /** "Meus mercados" — only the markets the user has explicitly pinned. */
    @GetMapping("/watched")
    public ResponseEntity<List<MarketResponse>> watched(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(watchedMarketService.listWatched(user));
    }

    @PostMapping("/watched/{cnpj}")
    public ResponseEntity<MarketResponse> pin(@AuthenticationPrincipal User user, @PathVariable String cnpj) {
        return ResponseEntity.ok(watchedMarketService.watch(user, cnpj));
    }

    @DeleteMapping("/watched/{cnpj}")
    public ResponseEntity<Void> unpin(@AuthenticationPrincipal User user, @PathVariable String cnpj) {
        watchedMarketService.unwatch(user, cnpj);
        return ResponseEntity.noContent().build();
    }

    /**
     * "Tela do mercado": the latest price the community registered for each product at this
     * store, one row per product. K-anonymity by tier is enforced in the query (grocery product
     * at a grocery store: 1 household; health product or non-grocery/unclassified store: K=3),
     * and rows carry only product, price and the DAY it was observed.
     */
    @GetMapping("/{cnpj}/prices")
    public ResponseEntity<Page<MarketProductPriceResponse>> prices(
            @PathVariable String cnpj,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "RECENT") MarketPricesSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(marketPricesService.latestPrices(cnpj,
                new MarketPricesService.Filter(query, category, days, sort, page, size)));
    }

    /** Set a household-only custom name for a market. */
    @PutMapping("/{cnpj}/name")
    public ResponseEntity<Map<String, String>> setName(@AuthenticationPrincipal User user,
                                                       @PathVariable String cnpj,
                                                       @Valid @RequestBody SetMarketNameRequest request) {
        return ResponseEntity.ok(Map.of("name", marketNameService.setName(user, cnpj, request.name())));
    }

    /** Remove the household's custom name for a market (reverts to the global name). */
    @DeleteMapping("/{cnpj}/name")
    public ResponseEntity<Void> clearName(@AuthenticationPrincipal User user, @PathVariable String cnpj) {
        marketNameService.clearName(user, cnpj);
        return ResponseEntity.noContent().build();
    }
}
