package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.RecordShoppingTripRequest;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.shopping.ShoppingTripRecorder;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shopping-trips")
@RequiredArgsConstructor
@Tag(name = "Shopping trips", description = "Record of a finished shopping trip (plan vs what happened)")
public class ShoppingTripController {

    private final ShoppingTripRecorder shoppingTripRecorder;

    /**
     * Stores a finished trip. Write-only: the record is kept for later analysis and no endpoint
     * reads it back. Safe to retry — the same {@code clientTripId} returns the stored id.
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> record(@AuthenticationPrincipal User user,
                                                    @Valid @RequestBody RecordShoppingTripRequest request) {
        var tripId = shoppingTripRecorder.record(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", tripId));
    }
}
