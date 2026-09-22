package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.CuratedOfferRequest;
import com.relyon.economizai.dto.response.CuratedOfferResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.admin.AdminEcommerceService;
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

import java.util.UUID;

/**
 * Admin curation of e-commerce offers (precision-first). ADMIN-only via SecurityConfig
 * ({@code /api/v1/admin/**}). Map an EAN to a specific online product + price + link.
 */
@Tag(name = "Admin - E-commerce")
@RestController
@RequestMapping("/api/v1/admin/ecommerce/offers")
@RequiredArgsConstructor
public class AdminEcommerceController {

    private final AdminEcommerceService adminEcommerceService;

    @Operation(summary = "List curated offers", description = "Paginated, newest edits first. Optional ?ean= filter.")
    @GetMapping
    public ResponseEntity<Page<CuratedOfferResponse>> list(
            @RequestParam(required = false) String ean,
            @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(adminEcommerceService.list(ean, pageable));
    }

    @Operation(summary = "Create a curated offer")
    @PostMapping
    public ResponseEntity<CuratedOfferResponse> create(@AuthenticationPrincipal User admin,
                                                       @Valid @RequestBody CuratedOfferRequest request) {
        return ResponseEntity.ok(adminEcommerceService.create(request, admin.getEmail()));
    }

    @Operation(summary = "Update a curated offer")
    @PutMapping("/{id}")
    public ResponseEntity<CuratedOfferResponse> update(@AuthenticationPrincipal User admin,
                                                       @PathVariable UUID id,
                                                       @Valid @RequestBody CuratedOfferRequest request) {
        return ResponseEntity.ok(adminEcommerceService.update(id, request, admin.getEmail()));
    }

    @Operation(summary = "Delete a curated offer")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        adminEcommerceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
