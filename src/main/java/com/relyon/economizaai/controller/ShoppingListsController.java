package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizaai.dto.request.CreateShoppingListRequest;
import com.relyon.economizaai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingListResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.shopping.ShoppingListService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shopping-lists")
@RequiredArgsConstructor
@Tag(name = "Shopping lists", description = "Persistent household shopping lists (CRUD + check-off). " +
        "For ad-hoc one-shot best-market grouping, see POST /api/v1/shopping-list/optimize (singular).")
public class ShoppingListsController {

    private final ShoppingListService service;

    @GetMapping
    public ResponseEntity<List<ShoppingListResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.listForHousehold(user));
    }

    /** Returns the household's single list (200) or 404 when there are 0 or 2+ lists. */
    @GetMapping("/sole")
    public ResponseEntity<ShoppingListResponse> sole(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.getSole(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShoppingListResponse> get(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(service.get(user, id));
    }

    @PostMapping
    public ResponseEntity<ShoppingListResponse> create(@AuthenticationPrincipal User user,
                                                       @Valid @RequestBody CreateShoppingListRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ShoppingListResponse> rename(@AuthenticationPrincipal User user,
                                                       @PathVariable UUID id,
                                                       @Valid @RequestBody UpdateShoppingListRequest request) {
        return ResponseEntity.ok(service.rename(user, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<ShoppingListResponse> addItem(@AuthenticationPrincipal User user,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody AddShoppingListItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addItem(user, id, request));
    }

    @PostMapping("/{id}/items/{itemId}/toggle")
    public ResponseEntity<ShoppingListResponse> toggleItem(@AuthenticationPrincipal User user,
                                                           @PathVariable UUID id,
                                                           @PathVariable UUID itemId) {
        return ResponseEntity.ok(service.toggleItem(user, id, itemId));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<ShoppingListResponse> removeItem(@AuthenticationPrincipal User user,
                                                           @PathVariable UUID id,
                                                           @PathVariable UUID itemId) {
        return ResponseEntity.ok(service.removeItem(user, id, itemId));
    }
}
