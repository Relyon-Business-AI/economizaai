package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.RedeemReferralRequest;
import com.relyon.economizaai.dto.response.ReferralStatusResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.referral.ReferralService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
@Tag(name = "Referrals", description = "Invite friends and earn Premium months")
public class ReferralController {

    private final ReferralService referralService;

    @GetMapping("/me")
    public ResponseEntity<ReferralStatusResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(referralService.statusFor(user));
    }

    @PostMapping("/redeem")
    public ResponseEntity<Void> redeem(@AuthenticationPrincipal User user,
                                       @Valid @RequestBody RedeemReferralRequest request) {
        referralService.redeem(user, request.code());
        return ResponseEntity.noContent().build();
    }
}
