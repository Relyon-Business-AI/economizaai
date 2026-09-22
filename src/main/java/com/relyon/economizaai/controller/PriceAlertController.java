package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizaai.dto.response.PriceAlertResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.alerts.PriceAlertService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Avise-me quando" — user-curated price-drop alerts. Rules fire when any
 * household's confirmed receipt contributes a matching low price to the
 * collaborative index.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Tag(name = "Price Alerts", description = "User price-drop alerts (avise-me quando)")
public class PriceAlertController {

    private final PriceAlertService priceAlertService;

    @PostMapping
    public ResponseEntity<PriceAlertResponse> create(@AuthenticationPrincipal User user,
                                                     @Valid @RequestBody CreatePriceAlertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priceAlertService.create(user, request));
    }

    @GetMapping
    public ResponseEntity<List<PriceAlertResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(priceAlertService.list(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        priceAlertService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
