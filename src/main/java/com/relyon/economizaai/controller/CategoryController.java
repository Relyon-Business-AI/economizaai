package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.CreateCategoryRequest;
import com.relyon.economizaai.dto.request.MigrateCategoryRequest;
import com.relyon.economizaai.dto.response.CategoryMigrationResponse;
import com.relyon.economizaai.dto.response.CategoryResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.CustomCategoryService;
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
 * Categories the household can use: the global enum plus its own custom
 * categories, and the migration of products between them. Custom categories
 * are household-scoped (overlay the global enum; don't affect other households).
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Global + household custom categories and product migration")
public class CategoryController {

    private final CustomCategoryService customCategoryService;

    /** Global enum categories + this household's custom categories. */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(customCategoryService.list(user));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal User user,
                                                   @Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customCategoryService.create(user, request.name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        customCategoryService.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    /** Migrate the selected products into a target category (global enum or custom). */
    @PostMapping("/migrate")
    public ResponseEntity<CategoryMigrationResponse> migrate(@AuthenticationPrincipal User user,
                                                             @Valid @RequestBody MigrateCategoryRequest request) {
        return ResponseEntity.ok(customCategoryService.migrate(
                user, request.productIds(), request.targetCategory(), request.targetCustomCategoryId()));
    }
}
