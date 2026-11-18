package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import com.relyon.economizaai.service.exposure.PriceExposureService;
import com.relyon.economizaai.service.exposure.ShownPrice;
import com.relyon.economizaai.service.notifications.NotifiedDealsService;
import com.relyon.economizaai.service.priceindex.DealsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * "Ofertas pra você" screen — the ranked, currently-relevant discounts for the
 * authenticated user's household. Built on the collaborative price index and
 * fully k-anonymity guarded (no sub-K community price is ever disclosed).
 *
 * <p>The app reports SCREEN_OPENED on open and DEAL_VIEWED / DEAL_TAPPED on
 * interaction itself, via {@code POST /api/v1/notifications/events}. The prices
 * served here are also logged as shown ({@link PriceExposureService}).
 *
 * <p>With {@code notificationId} the list is narrowed to the deals that notification announced
 * (the tap on "X mais barato e mais 2 ofertas"), looked up in the same wide scope the digest
 * used so none of them is cut off by the caller's paging.
 */
@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Ranked, k-anon-guarded discounts relevant to the household (\"Ofertas pra você\")")
public class DealsController {

    private static final int ANNOUNCED_DEALS_SCOPE = 50;

    private final DealsService dealsService;
    private final PriceExposureService priceExposureService;
    private final NotifiedDealsService notifiedDealsService;

    @GetMapping
    public ResponseEntity<List<DealResponse>> deals(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "false") boolean includeNearby,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) UUID notificationId) {
        var deals = notificationId == null
                ? dealsService.findDeals(user, includeNearby, radiusKm, limit, lat, lng)
                : announcedBy(user, notificationId, lat, lng);
        priceExposureService.recordShown(user, PriceExposureSurface.DEALS, ShownPrice.ofDeals(deals));
        return ResponseEntity.ok(deals);
    }

    private List<DealResponse> announcedBy(User user, UUID notificationId, BigDecimal lat, BigDecimal lng) {
        var currentDeals = dealsService.findDeals(user, true, null, ANNOUNCED_DEALS_SCOPE, lat, lng);
        return notifiedDealsService.onlyAnnouncedBy(user, notificationId, currentDeals);
    }
}
