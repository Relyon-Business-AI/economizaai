package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.priceindex.DealsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Ofertas pra você" screen — the ranked, currently-relevant discounts for the
 * authenticated user's household. Built on the collaborative price index and
 * fully k-anonymity guarded (no sub-K community price is ever disclosed).
 *
 * <p>Read-only: this endpoint emits no telemetry. The app reports
 * SCREEN_OPENED on open and DEAL_VIEWED / DEAL_TAPPED on interaction itself,
 * via {@code POST /api/v1/notifications/events}.
 */
@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Ranked, k-anon-guarded discounts relevant to the household (\"Ofertas pra você\")")
public class DealsController {

    private final DealsService dealsService;

    @GetMapping
    public ResponseEntity<List<DealResponse>> deals(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "false") boolean includeNearby,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(dealsService.findDeals(user, includeNearby, radiusKm, limit));
    }
}
