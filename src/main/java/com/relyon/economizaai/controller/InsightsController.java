package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.InsightsQueryResponse;
import com.relyon.economizaai.dto.response.MarketDiscountResponse;
import com.relyon.economizaai.dto.response.PriceHistoryResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.InsightsGroupBy;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.InsightsQueryService;
import com.relyon.economizaai.service.InsightsQueryService.QueryFilters;
import com.relyon.economizaai.service.InsightsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
@Tag(name = "Insights", description = "Spending dashboards, top markets/categories, price history")
public class InsightsController {

    private final InsightsService insightsService;
    private final InsightsQueryService insightsQueryService;

    @GetMapping("/spend")
    public ResponseEntity<SpendInsightsResponse> spend(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "ALL") MarketScope scope) {
        return ResponseEntity.ok(insightsService.spend(user, from, to, scope));
    }

    @GetMapping("/markets/top")
    public ResponseEntity<List<SpendInsightsResponse.MarketBucket>> topMarkets(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "ALL") MarketScope scope) {
        return ResponseEntity.ok(insightsService.topMarkets(user, from, to, limit, scope));
    }

    @GetMapping("/markets/top-discounts")
    public ResponseEntity<List<MarketDiscountResponse>> topMarketsByDiscount(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "ALL") MarketScope scope) {
        return ResponseEntity.ok(insightsService.topMarketsByDiscount(user, from, to, limit, scope));
    }

    @GetMapping("/categories/top")
    public ResponseEntity<List<SpendInsightsResponse.CategoryBucket>> topCategories(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "HOUSEHOLD") CategoryView categoryView,
            @RequestParam(defaultValue = "ALL") MarketScope scope) {
        return ResponseEntity.ok(insightsService.topCategories(user, from, to, limit, categoryView, scope));
    }

    @GetMapping("/products/{productId}/price-history")
    public ResponseEntity<PriceHistoryResponse> priceHistory(
            @AuthenticationPrincipal User user,
            @PathVariable UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(insightsService.priceHistory(user, productId, from, to));
    }

    /**
     * Flexible spend slicer. Combine any subset of filters with a single
     * {@code groupBy} dimension. Multi-value filters accept lists (Spring MVC
     * binds repeated query params or comma-separated values to a List). Range
     * filters use paired min/max params.
     *
     * <p>See {@link InsightsQueryResponse} for the shape and
     * {@link InsightsGroupBy} for the available dimensions.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code ?from=2026-04-01T00:00:00&to=2026-04-30T23:59:59&groupBy=WEEK} —
     *       weekly spend in April.</li>
     *   <li>{@code ?marketCnpj=93015006005182&groupBy=CATEGORY} —
     *       category breakdown at one specific store, all-time.</li>
     *   <li>{@code ?category=GROCERIES&category=BEVERAGES&groupBy=PRODUCT&limit=20} —
     *       top 20 products across multiple categories.</li>
     *   <li>{@code ?minReceiptTotal=100&maxReceiptTotal=500&groupBy=MARKET} —
     *       which markets get my mid-size shopping trips (R$100–500).</li>
     * </ul>
     */
    @GetMapping("/query")
    public ResponseEntity<InsightsQueryResponse> query(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<String> marketCnpj,
            @RequestParam(required = false) List<String> marketCnpjRoot,
            @RequestParam(required = false) List<ProductCategory> category,
            @RequestParam(required = false) List<UUID> productId,
            @RequestParam(required = false) List<String> ean,
            @RequestParam(required = false) BigDecimal minReceiptTotal,
            @RequestParam(required = false) BigDecimal maxReceiptTotal,
            @RequestParam(required = false) InsightsGroupBy groupBy,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "HOUSEHOLD") CategoryView categoryView) {
        var filters = QueryFilters.fromRequest(from, to, marketCnpj, marketCnpjRoot, category,
                productId, ean, minReceiptTotal, maxReceiptTotal, groupBy, limit, categoryView);
        return ResponseEntity.ok(insightsQueryService.query(user, filters));
    }
}
