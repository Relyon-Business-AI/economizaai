package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.LogManualPurchaseRequest;
import com.relyon.economizaai.dto.request.SnoozeProductRequest;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 3 — Consumption Intelligence. Predictions per product (when will
 * the household run out?), derived suggested shopping list, snoozes,
 * manual-purchase log. Volume-gated at the service layer — products with
 * too little history are silently skipped.
 */
@RestController
@RequestMapping("/api/v1/consumption")
@RequiredArgsConstructor
@Tag(name = "Consumption", description = "Predictive purchase intelligence per household")
public class ConsumptionController {

    private final ConsumptionIntelligenceService service;

    @GetMapping("/predictions")
    public ResponseEntity<List<ConsumptionPredictionResponse>> predictions(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.predict(user));
    }

    @GetMapping("/suggested-list")
    public ResponseEntity<SuggestedShoppingListResponse> suggested(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "false") boolean includeUpcoming,
            @RequestParam(defaultValue = "5") int upcomingLimit) {
        return ResponseEntity.ok(service.suggestedList(user, includeUpcoming, upcomingLimit));
    }

    @PostMapping("/snooze/{productId}")
    public ResponseEntity<Void> snooze(@AuthenticationPrincipal User user,
                                       @PathVariable UUID productId,
                                       @Valid @RequestBody SnoozeProductRequest request) {
        service.snooze(user, productId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/snooze/{productId}")
    public ResponseEntity<Void> unsnooze(@AuthenticationPrincipal User user, @PathVariable UUID productId) {
        service.unsnooze(user, productId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/manual-purchase")
    public ResponseEntity<Void> logManualPurchase(@AuthenticationPrincipal User user,
                                                  @Valid @RequestBody LogManualPurchaseRequest request) {
        service.logManualPurchase(user, request);
        return ResponseEntity.noContent().build();
    }
}
