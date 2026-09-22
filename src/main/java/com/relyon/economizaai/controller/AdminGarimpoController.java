package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.GarimpoWatchRequest;
import com.relyon.economizaai.dto.response.GarimpoMarketplaceResponse;
import com.relyon.economizaai.dto.response.GarimpoSearchResponse;
import com.relyon.economizaai.dto.response.GarimpoSnapshotResponse;
import com.relyon.economizaai.dto.response.GarimpoWatchResponse;
import com.relyon.economizaai.dto.response.GarimpoWatchRunResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.garimpo.GarimpoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import java.util.UUID;

/**
 * Garimpo de promoções — marketplace deal hunting. Live term search (feeds the price
 * history), the history itself, and watches (standing searches whose hits alert a
 * webhook, e.g. a group bot). ADMIN-only via SecurityConfig ({@code /api/v1/admin/**}).
 */
@Tag(name = "Admin - Garimpo")
@RestController
@RequestMapping("/api/v1/admin/garimpo")
@RequiredArgsConstructor
public class AdminGarimpoController {

    private final GarimpoService garimpoService;

    @Operation(summary = "Search a marketplace by term",
            description = "Live search on the given marketplace (default mercadolivre). Results are normalized "
                    + "(price, discount, affiliate link) and changed prices are appended to the price history. "
                    + "Optional minDiscount filters the RESPONSE to products discounted at least that percent.")
    @GetMapping("/search")
    public ResponseEntity<GarimpoSearchResponse> search(
            @RequestParam String q,
            @RequestParam(required = false) String marketplace,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Integer minDiscount) {
        return ResponseEntity.ok(garimpoService.search(q, marketplace, offset, limit, minDiscount));
    }

    @Operation(summary = "List pluggable marketplaces",
            description = "Every marketplace the garimpo can hunt on and whether it has live credentials.")
    @GetMapping("/marketplaces")
    public ResponseEntity<List<GarimpoMarketplaceResponse>> marketplaces() {
        return ResponseEntity.ok(garimpoService.marketplaces());
    }

    @Operation(summary = "Price history of a marketplace product",
            description = "The append-only change-log of observed prices (newest first) — one row per price change.")
    @GetMapping("/products/{marketplace}/{externalId}/history")
    public ResponseEntity<Page<GarimpoSnapshotResponse>> history(
            @PathVariable String marketplace,
            @PathVariable String externalId,
            @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(garimpoService.history(marketplace, externalId, pageable));
    }

    @Operation(summary = "List watches", description = "Paginated, newest first.")
    @GetMapping("/watches")
    public ResponseEntity<Page<GarimpoWatchResponse>> listWatches(
            @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(garimpoService.listWatches(pageable));
    }

    @Operation(summary = "Create a watch",
            description = "A standing search swept on a schedule. Needs a target price and/or a minimum discount; "
                    + "meeting either one is a hit and new hits are POSTed to the configured webhook.")
    @PostMapping("/watches")
    public ResponseEntity<GarimpoWatchResponse> createWatch(@AuthenticationPrincipal User admin,
                                                            @Valid @RequestBody GarimpoWatchRequest request) {
        return ResponseEntity.ok(garimpoService.createWatch(request, admin.getEmail()));
    }

    @Operation(summary = "Update a watch")
    @PutMapping("/watches/{id}")
    public ResponseEntity<GarimpoWatchResponse> updateWatch(@PathVariable UUID id,
                                                            @Valid @RequestBody GarimpoWatchRequest request) {
        return ResponseEntity.ok(garimpoService.updateWatch(id, request));
    }

    @Operation(summary = "Delete a watch", description = "Hard delete; the price history stays.")
    @DeleteMapping("/watches/{id}")
    public ResponseEntity<Void> deleteWatch(@PathVariable UUID id) {
        garimpoService.deleteWatch(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Run a watch now",
            description = "Runs the watch immediately (also refreshes the price history) and returns the hits. "
                    + "Useful to validate a new watch and the webhook wiring without waiting for the sweep.")
    @PostMapping("/watches/{id}/run")
    public ResponseEntity<GarimpoWatchRunResponse> runWatchNow(@PathVariable UUID id) {
        return ResponseEntity.ok(garimpoService.runWatchNow(id));
    }
}
