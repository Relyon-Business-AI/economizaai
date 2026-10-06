package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.MerchantPriceComparisonResponse;
import com.relyon.economizaai.dto.response.MerchantProfileResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.merchant.MerchantPanelService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant mini-panel (docs/MERCHANT_ACCOUNTS.md, Fase 1). Path-gated via
 * SecurityConfig (/api/v1/merchant/** → hasRole("MERCHANT")), so no per-method
 * guard needed. Invisible to regular users — no FE exposes these yet.
 */
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
@Tag(name = "Merchant", description = "Operations restricted to ROLE_MERCHANT")
public class MerchantController {

    private final MerchantPanelService merchantPanelService;

    @GetMapping("/profile")
    public ResponseEntity<MerchantProfileResponse> profile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(merchantPanelService.profile(user));
    }

    @GetMapping("/price-comparison")
    public ResponseEntity<MerchantPriceComparisonResponse> priceComparison(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(merchantPanelService.priceComparison(user));
    }
}
