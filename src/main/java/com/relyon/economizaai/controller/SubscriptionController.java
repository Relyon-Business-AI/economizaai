package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.CheckoutResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.subscription.MercadoPagoCheckoutService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Premium subscription checkout (web)")
public class SubscriptionController {

    private final MercadoPagoCheckoutService checkoutService;

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(checkoutService.createCheckout(user));
    }
}
