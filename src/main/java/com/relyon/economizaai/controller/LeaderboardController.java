package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.LeaderboardOptInRequest;
import com.relyon.economizaai.dto.response.LeaderboardResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.LeaderboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Caçador de descontos" leaderboard. Full paths per method so the public + opt-in
 * endpoints and the admin one live together (admin path is ADMIN-gated by SecurityConfig).
 */
@Tag(name = "Leaderboard")
@RestController
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @Operation(summary = "Discount-hunters leaderboard (public)",
            description = "Households ranked by items bought below the community average. Public view lists only "
                    + "opted-in households; the caller always sees their own standing under `me`.")
    @GetMapping("/api/v1/leaderboard/discount-hunters")
    public ResponseEntity<LeaderboardResponse> discountHunters(@AuthenticationPrincipal User user,
                                                               @RequestParam(defaultValue = "30") int days) {
        var householdId = user.getHousehold() == null ? null : user.getHousehold().getId();
        return ResponseEntity.ok(leaderboardService.discountHunters(days, householdId));
    }

    @Operation(summary = "Opt in/out of the public leaderboard")
    @PatchMapping("/api/v1/leaderboard/opt-in")
    public ResponseEntity<Void> setOptIn(@AuthenticationPrincipal User user,
                                         @Valid @RequestBody LeaderboardOptInRequest request) {
        leaderboardService.setOptIn(user, request.optIn());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Discount-hunters leaderboard (admin)",
            description = "All households (opted in or not), handles are emails. ADMIN only.")
    @GetMapping("/api/v1/admin/leaderboard/discount-hunters")
    public ResponseEntity<LeaderboardResponse> discountHuntersAdmin(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(leaderboardService.discountHuntersAdmin(days));
    }
}
