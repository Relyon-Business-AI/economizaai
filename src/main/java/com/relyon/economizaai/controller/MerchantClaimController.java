package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SubmitMerchantClaimRequest;
import com.relyon.economizaai.dto.request.VerifyMerchantClaimRequest;
import com.relyon.economizaai.dto.response.MerchantClaimResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.merchant.MerchantClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-serve "sou este mercado" (docs/MERCHANT_ACCOUNTS.md, Fase 1.5). Open to
 * ANY authenticated user — the claimant is a regular USER until the claim is
 * approved, which promotes them to MERCHANT and grants the chain.
 */
@RestController
@RequestMapping("/api/v1/merchant-claims")
@RequiredArgsConstructor
@Tag(name = "Merchant claims", description = "Self-serve merchant profile claims")
public class MerchantClaimController {

    private final MerchantClaimService merchantClaimService;

    @Operation(summary = "Claim a merchant",
            description = "Starts a claim for the chain of the given CNPJ. When the Receita has a company "
                    + "e-mail, a verification code is sent there (status AWAITING_CODE); otherwise the claim "
                    + "waits for an admin verdict (status PENDING_REVIEW).")
    @PostMapping
    public ResponseEntity<MerchantClaimResponse> submit(@AuthenticationPrincipal User user,
                                                        @Valid @RequestBody SubmitMerchantClaimRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(merchantClaimService.submit(user, request));
    }

    @GetMapping
    public ResponseEntity<List<MerchantClaimResponse>> myClaims(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(merchantClaimService.myClaims(user));
    }

    @Operation(summary = "Verify the claim code",
            description = "Checks the 6-digit code sent to the company e-mail. On success the claim is "
                    + "approved: the user becomes MERCHANT, the chain is granted and the marketing "
                    + "subscription opens (free until the launch-promo date).")
    @PostMapping("/{id}/verify")
    public ResponseEntity<MerchantClaimResponse> verify(@AuthenticationPrincipal User user,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody VerifyMerchantClaimRequest request) {
        return ResponseEntity.ok(merchantClaimService.verify(user, id, request.code()));
    }
}
