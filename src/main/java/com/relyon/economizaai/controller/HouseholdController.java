package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.HouseholdService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/households")
@RequiredArgsConstructor
@Tag(name = "Households", description = "Shared purchase-history grouping (couple, family)")
public class HouseholdController {

    private final HouseholdService householdService;

    @GetMapping("/me")
    public ResponseEntity<HouseholdResponse> getMine(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(householdService.getMine(user));
    }

    @PostMapping("/join")
    public ResponseEntity<HouseholdResponse> join(@AuthenticationPrincipal User user,
                                                  @Valid @RequestBody JoinHouseholdRequest request) {
        return ResponseEntity.ok(householdService.join(user, request));
    }

    @PostMapping("/leave")
    public ResponseEntity<HouseholdResponse> leave(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(householdService.leave(user));
    }

    @PostMapping("/me/invite-code/regenerate")
    public ResponseEntity<HouseholdResponse> regenerateInviteCode(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(householdService.regenerateInviteCode(user));
    }

    @DeleteMapping("/me/members/{memberId}")
    public ResponseEntity<HouseholdResponse> removeMember(@AuthenticationPrincipal User user,
                                                          @PathVariable UUID memberId) {
        return ResponseEntity.ok(householdService.removeMember(user, memberId));
    }
}
