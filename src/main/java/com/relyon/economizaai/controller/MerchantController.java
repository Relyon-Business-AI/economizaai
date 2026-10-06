package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.MerchantPromoBatchRequest;
import com.relyon.economizaai.dto.request.MerchantPromoRequest;
import com.relyon.economizaai.dto.response.MerchantPriceComparisonResponse;
import com.relyon.economizaai.dto.response.MerchantProfileResponse;
import com.relyon.economizaai.dto.response.MerchantPromoImportResponse;
import com.relyon.economizaai.dto.response.MerchantPromoResponse;
import com.relyon.economizaai.dto.response.MerchantSubscriptionResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantPromoSource;
import com.relyon.economizaai.service.merchant.MerchantPanelService;
import com.relyon.economizaai.service.merchant.MerchantPromoImportService;
import com.relyon.economizaai.service.merchant.MerchantPromoService;
import com.relyon.economizaai.service.merchant.MerchantSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Merchant panel (docs/MERCHANT_ACCOUNTS.md). Path-gated via SecurityConfig
 * (/api/v1/merchant/** → hasRole("MERCHANT")), so no per-method guard needed.
 * Invisible to regular users — no consumer FE exposes these.
 */
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
@Tag(name = "Merchant", description = "Operations restricted to ROLE_MERCHANT")
public class MerchantController {

    private final MerchantPanelService merchantPanelService;
    private final MerchantPromoService merchantPromoService;
    private final MerchantPromoImportService merchantPromoImportService;
    private final MerchantSubscriptionService merchantSubscriptionService;

    @GetMapping("/profile")
    public ResponseEntity<MerchantProfileResponse> profile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(merchantPanelService.profile(user));
    }

    @GetMapping("/price-comparison")
    public ResponseEntity<MerchantPriceComparisonResponse> priceComparison(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(merchantPanelService.priceComparison(user));
    }

    /** Marketing-account standing per chain — powers the "grátis até {freeUntil}" modal. */
    @GetMapping("/subscription")
    public ResponseEntity<List<MerchantSubscriptionResponse>> subscription(@AuthenticationPrincipal User user) {
        var subscriptions = merchantSubscriptionService
                .forChains(merchantPromoService.grantedChains(user)).stream()
                .map(MerchantSubscriptionResponse::from)
                .toList();
        return ResponseEntity.ok(subscriptions);
    }

    @GetMapping("/promos")
    public ResponseEntity<Page<MerchantPromoResponse>> listPromos(@AuthenticationPrincipal User user,
                                                                  @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(merchantPromoService.list(user, pageable));
    }

    @PostMapping("/promos")
    public ResponseEntity<MerchantPromoResponse> createPromo(@AuthenticationPrincipal User user,
                                                             @Valid @RequestBody MerchantPromoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(merchantPromoService.create(user, request, MerchantPromoSource.MANUAL));
    }

    @PutMapping("/promos/{id}")
    public ResponseEntity<MerchantPromoResponse> updatePromo(@AuthenticationPrincipal User user,
                                                             @PathVariable UUID id,
                                                             @Valid @RequestBody MerchantPromoRequest request) {
        return ResponseEntity.ok(merchantPromoService.update(user, id, request));
    }

    @DeleteMapping("/promos/{id}")
    public ResponseEntity<Void> deletePromo(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        merchantPromoService.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Import promos from a file",
            description = "CSV or XLSX price-table export from the market's ERP. Columns (pt aliases, "
                    + "accent-insensitive): ean, preco[_promocional], preco_normal?, inicio, fim, descricao?. "
                    + "Per-row report: bad rows are listed, good rows import.")
    @PostMapping("/promos/import")
    public ResponseEntity<MerchantPromoImportResponse> importPromos(@AuthenticationPrincipal User user,
                                                                    @RequestParam("file") MultipartFile file,
                                                                    @RequestParam(required = false) String cnpjRoot) {
        return ResponseEntity.ok(merchantPromoImportService.importFile(user, cnpjRoot, file));
    }

    @Operation(summary = "Import promos as JSON batch",
            description = "ERP/API integration path — same per-row semantics as the file import.")
    @PostMapping("/promos/batch")
    public ResponseEntity<MerchantPromoImportResponse> importBatch(@AuthenticationPrincipal User user,
                                                                   @RequestParam(required = false) String cnpjRoot,
                                                                   @Valid @RequestBody MerchantPromoBatchRequest request) {
        return ResponseEntity.ok(merchantPromoImportService.importBatch(user, cnpjRoot, request.promos()));
    }
}
