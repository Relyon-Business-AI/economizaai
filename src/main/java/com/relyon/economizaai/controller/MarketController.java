package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SetMarketNameRequest;
import com.relyon.economizaai.dto.response.MarketResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    /** Catalogue for the picker UI: visited + watched + (optionally) nearby. */
    @GetMapping
    public ResponseEntity<List<MarketResponse>> list(@AuthenticationPrincipal User user,
                                                     @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(watchedMarketService.listForPicker(user, radiusKm));
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
