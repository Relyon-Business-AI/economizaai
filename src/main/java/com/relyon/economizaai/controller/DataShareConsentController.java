package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.ConsentResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.DataShareConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/households/consents")
@RequiredArgsConstructor
@Tag(name = "Data-share consents", description = "Approve/deny another member taking a copy of your data on split")
public class DataShareConsentController {

    private final DataShareConsentService consentService;

    @Operation(summary = "List consent requests awaiting MY decision",
            description = "Requests where I am the grantor — another member wants to take a copy of my data.")
    @GetMapping("/pending")
    public ResponseEntity<List<ConsentResponse>> pending(@AuthenticationPrincipal User user) {
        var pending = consentService.pendingForGrantor(user).stream().map(ConsentResponse::from).toList();
        return ResponseEntity.ok(pending);
    }

    @Operation(summary = "Approve a data-share request",
            description = "Lets the requester take a copy of my data into their household. 200 = approved; " +
                    "400 = not mine / already resolved / expired.")
    @PostMapping("/{consentId}/approve")
    public ResponseEntity<ConsentResponse> approve(@AuthenticationPrincipal User user,
                                                   @PathVariable UUID consentId) {
        return ResponseEntity.ok(ConsentResponse.from(consentService.approve(user, consentId)));
    }

    @Operation(summary = "Deny a data-share request",
            description = "Refuses the request; no data is copied. 200 = denied; 400 = not mine / already resolved.")
    @PostMapping("/{consentId}/deny")
    public ResponseEntity<ConsentResponse> deny(@AuthenticationPrincipal User user,
                                                @PathVariable UUID consentId) {
        return ResponseEntity.ok(ConsentResponse.from(consentService.deny(user, consentId)));
    }
}
