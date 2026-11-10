package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.dto.request.RenameHouseholdRequest;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.dto.response.InvitePreviewResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.HouseholdService;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/households")
@RequiredArgsConstructor
@Tag(name = "Households", description = "Shared purchase-history grouping (couple, family)")
public class HouseholdController {

    private final HouseholdService householdService;
    private final ProfilePictureService profilePictureService;

    /**
     * A fellow member's profile picture (or their generated initials avatar). Household
     * members already see each other's names and e-mails; the photo is the same scope.
     * Same headers as {@code GET /users/me/profile-picture}.
     */
    @GetMapping("/me/members/{memberId}/profile-picture")
    public ResponseEntity<byte[]> memberProfilePicture(@AuthenticationPrincipal User user,
                                                       @PathVariable UUID memberId,
                                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var member = householdService.memberOf(user, memberId);
        return ProfilePictureResponses.respond(profilePictureService, member, ifNoneMatch);
    }

    @GetMapping("/me")
    public ResponseEntity<HouseholdResponse> getMine(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(householdService.getMine(user));
    }

    @PatchMapping("/me")
    @Operation(summary = "Rename my household (any member). Blank name clears it.")
    public ResponseEntity<HouseholdResponse> rename(@AuthenticationPrincipal User user,
                                                    @Valid @RequestBody RenameHouseholdRequest request) {
        return ResponseEntity.ok(householdService.rename(user, request.name()));
    }

    @GetMapping("/invites/{code}")
    @Operation(summary = "Preview what an invite code unlocks (household name, host, member count) before joining.")
    public ResponseEntity<InvitePreviewResponse> previewInvite(@PathVariable String code) {
        return ResponseEntity.ok(householdService.previewInvite(code));
    }

    @PostMapping("/join")
    @Operation(summary = "Join a household by invite code. Your data moves with you. " +
            "402 when the household hit the FREE member cap (Premium lifts it), 400 at the absolute cap.")
    public ResponseEntity<HouseholdResponse> join(@AuthenticationPrincipal User user,
                                                  @Valid @RequestBody JoinHouseholdRequest request) {
        return ResponseEntity.ok(householdService.join(user, request));
    }

    @PostMapping("/leave")
    @Operation(summary = "Leave the household, taking back what you brought. 400 if you're alone in it.")
    public ResponseEntity<HouseholdResponse> leave(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(householdService.leave(user));
    }

    @PostMapping("/me/invite-code/regenerate")
    public ResponseEntity<HouseholdResponse> regenerateInviteCode(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(householdService.regenerateInviteCode(user));
    }

    @DeleteMapping("/me/members/{memberId}")
    @Operation(summary = "Remove a member (host only — 403 otherwise). They land back in their own household with their data.")
    public ResponseEntity<HouseholdResponse> removeMember(@AuthenticationPrincipal User user,
                                                          @PathVariable UUID memberId) {
        return ResponseEntity.ok(householdService.removeMember(user, memberId));
    }
}
