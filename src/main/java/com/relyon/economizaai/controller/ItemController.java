package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.PurchasedItemResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.CustomCategoryService;
import com.relyon.economizaai.service.ItemQueryService;
import com.relyon.economizaai.service.ItemQueryService.ItemFilters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Purchased line items, flattened across all of the household's receipts.
 *
 * <p>Item-level companion to {@code /receipts} (receipt rows) and
 * {@code /insights/query} (aggregates). Same filter vocabulary as the insights
 * slicer — every filter is optional and multi-value filters OR internally —
 * so a single endpoint serves "items in a category", "items of a product",
 * "items at a market", etc. Results are CONFIRMED purchases only, newest first.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code ?category=MEAT_DAIRY} — every dairy/meat item I've bought.</li>
 *   <li>{@code ?productId=<uuid>&from=2026-01-01T00:00:00} — one product's
 *       purchase line items this year.</li>
 *   <li>{@code ?marketCnpj=93015006005182&category=CLEANING} — cleaning items
 *       bought at one store.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
@Tag(name = "Items", description = "Purchased line items across all receipts (filterable, paginated)")
public class ItemController {

    private final ItemQueryService itemQueryService;
    private final CustomCategoryService customCategoryService;

    @GetMapping
    public ResponseEntity<Page<PurchasedItemResponse>> list(
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
            @RequestParam(required = false) UUID customCategoryId,
            @RequestParam(defaultValue = "HOUSEHOLD") CategoryView categoryView,
            @PageableDefault(size = 20) Pageable pageable) {
        // Filtering by a custom category resolves to the household's products
        // migrated into it; combine with any explicit productId filter.
        var productIds = productId;
        if (customCategoryId != null) {
            var inCustom = customCategoryService.productIdsIn(user, customCategoryId);
            if (inCustom.isEmpty()) {
                return ResponseEntity.ok(Page.empty(pageable));
            }
            productIds = inCustom;
        }
        var filters = ItemFilters.fromRequest(from, to, marketCnpj, marketCnpjRoot, category,
                productIds, ean, minReceiptTotal, maxReceiptTotal, categoryView);
        return ResponseEntity.ok(itemQueryService.query(user, filters, pageable));
    }
}
