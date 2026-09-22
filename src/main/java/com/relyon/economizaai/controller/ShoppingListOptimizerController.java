package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.OptimizeShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.shopping.ShoppingListOptimizer;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shopping-list")
@RequiredArgsConstructor
@Tag(name = "Shopping list optimizer", description = "Stateless multi-market basket optimization (PRO-52)")
public class ShoppingListOptimizerController {

    private final ShoppingListOptimizer optimizer;

    @PostMapping("/optimize")
    public ResponseEntity<ShoppingPlanResponse> optimize(@AuthenticationPrincipal User user,
                                                         @Valid @RequestBody OptimizeShoppingListRequest request) {
        return ResponseEntity.ok(optimizer.optimize(user, request));
    }
}
