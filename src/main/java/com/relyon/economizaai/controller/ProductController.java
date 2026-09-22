package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.CreateAliasRequest;
import com.relyon.economizaai.dto.request.CreateProductRequest;
import com.relyon.economizaai.dto.request.UpdateProductRequest;
import com.relyon.economizaai.dto.response.EanLookupResponse;
import com.relyon.economizaai.dto.response.HouseholdProductResponse;
import com.relyon.economizaai.dto.response.ProductMarketPriceResponse;
import com.relyon.economizaai.dto.response.ProductResponse;
import com.relyon.economizaai.dto.response.UnmatchedItemResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.HouseholdProductService;
import com.relyon.economizaai.service.ProductRecentViewService;
import com.relyon.economizaai.service.ProductService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Canonical product registry and alias management")
public class ProductController {

    private final ProductService productService;
    private final HouseholdProductService householdProductService;
    private final ProductRecentViewService recentViewService;

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> search(@AuthenticationPrincipal User user,
                                                        @RequestParam(required = false) String query,
                                                        @RequestParam(required = false) Integer lastProducts,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        if (lastProducts != null && user != null) {
            return ResponseEntity.ok(new PageImpl<>(recentViewService.listRecent(user, lastProducts)));
        }
        return ResponseEntity.ok(productService.search(query, user, pageable));
    }

    /**
     * Products this household has actually bought (not the global catalog), newest purchase first.
     * Optional {@code query} filters by product name or brand (case-insensitive substring).
     */
    @GetMapping("/mine")
    public ResponseEntity<List<HouseholdProductResponse>> mine(@AuthenticationPrincipal User user,
                                                               @RequestParam(required = false) String query) {
        return ResponseEntity.ok(householdProductService.listHouseholdProducts(user, query));
    }

    /**
     * Where this product can be bought and at what price — watched markets always,
     * nearby markets when {@code includeNearby=true}. Own visited markets show the
     * exact last paid price; community markets show the k-anon-guarded median.
     */
    @GetMapping("/{id}/markets")
    public ResponseEntity<List<ProductMarketPriceResponse>> markets(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeNearby,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(householdProductService.productMarkets(user, id, includeNearby, radiusKm));
    }

    /** Record that the authenticated user opened this product's detail screen. */
    @PostMapping("/{id}/view")
    public ResponseEntity<Void> recordView(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        recentViewService.track(user, id);
        return ResponseEntity.noContent().build();
    }

    /** Recently viewed products for the authenticated user, newest first. */
    @GetMapping("/recently-viewed")
    public ResponseEntity<List<ProductResponse>> recentlyViewed(@AuthenticationPrincipal User user,
                                                                @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(recentViewService.listRecent(user, limit));
    }

    @GetMapping("/unmatched")
    public ResponseEntity<List<UnmatchedItemResponse>> unmatched(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(productService.listUnmatched(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> get(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(productService.get(id, user));
    }

    /**
     * Barcode-scan lookup. Returns the tracked product when we have one for the
     * EAN (follow up with {@code /price-index/products/{id}/best-markets} for
     * nearby prices), or an EAN-catalog preview when the barcode is known but
     * has no price data yet. 404 when unknown to both.
     */
    @GetMapping("/by-ean/{ean}")
    public ResponseEntity<EanLookupResponse> lookupByEan(@AuthenticationPrincipal User user,
                                                         @PathVariable String ean) {
        return ResponseEntity.ok(productService.lookupByEan(ean, user));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    @PostMapping("/{id}/aliases")
    public ResponseEntity<ProductResponse> addAlias(@AuthenticationPrincipal User user,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody CreateAliasRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addAlias(user, id, request));
    }
}
