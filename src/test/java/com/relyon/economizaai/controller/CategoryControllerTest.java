package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.CreateCategoryRequest;
import com.relyon.economizaai.dto.request.MigrateCategoryRequest;
import com.relyon.economizaai.dto.response.CategoryMigrationResponse;
import com.relyon.economizaai.dto.response.CategoryResponse;
import com.relyon.economizaai.exception.CustomCategoryNotFoundException;
import com.relyon.economizaai.exception.InvalidCategoryMigrationException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.CustomCategoryService;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
class CategoryControllerTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private CustomCategoryService customCategoryService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void list_returnsCategories() throws Exception {
        when(customCategoryService.list(any(User.class))).thenReturn(List.of(
                new CategoryResponse(null, "GROCERIES", false),
                new CategoryResponse(UUID.randomUUID(), "Frutas", true)));

        mockMvc.perform(get("/api/v1/categories")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("GROCERIES"))
                .andExpect(jsonPath("$[0].custom").value(false))
                .andExpect(jsonPath("$[1].name").value("Frutas"))
                .andExpect(jsonPath("$[1].custom").value(true));
    }

    @Test
    void create_returnsCreatedCategory() throws Exception {
        var id = UUID.randomUUID();
        when(customCategoryService.create(any(User.class), eq("Frutas")))
                .thenReturn(new CategoryResponse(id, "Frutas", true));

        var body = objectMapper.writeValueAsString(new CreateCategoryRequest("Frutas"));
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Frutas"))
                .andExpect(jsonPath("$.custom").value(true));
    }

    @Test
    void create_blankName_returns400() throws Exception {
        var body = objectMapper.writeValueAsString(new CreateCategoryRequest("  "));
        when(localizedMessageService.translate("validation.failed")).thenReturn("Validation failed");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        var id = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/categories/{id}", id)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_unknownCategory_returns404() throws Exception {
        var id = UUID.randomUUID();
        doThrow(new CustomCategoryNotFoundException())
                .when(customCategoryService).delete(any(User.class), eq(id));
        when(localizedMessageService.translate(any(CustomCategoryNotFoundException.class)))
                .thenReturn("Custom category not found");

        mockMvc.perform(delete("/api/v1/categories/{id}", id)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void migrate_returnsOutcome() throws Exception {
        var productId = UUID.randomUUID();
        when(customCategoryService.migrate(any(User.class), anyList(),
                eq(ProductCategory.PRODUCE), eq(null)))
                .thenReturn(new CategoryMigrationResponse(2, 1));

        var body = objectMapper.writeValueAsString(
                new MigrateCategoryRequest(List.of(productId), ProductCategory.PRODUCE, null));
        mockMvc.perform(post("/api/v1/categories/migrate")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrated").value(2))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    void migrate_emptyProductIds_returns400() throws Exception {
        var body = objectMapper.writeValueAsString(
                new MigrateCategoryRequest(List.of(), ProductCategory.PRODUCE, null));
        when(localizedMessageService.translate("validation.failed")).thenReturn("Validation failed");

        mockMvc.perform(post("/api/v1/categories/migrate")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void migrate_invalidTarget_returns400() throws Exception {
        var productId = UUID.randomUUID();
        when(customCategoryService.migrate(any(User.class), anyList(), any(), any()))
                .thenThrow(new InvalidCategoryMigrationException());
        when(localizedMessageService.translate(any(InvalidCategoryMigrationException.class)))
                .thenReturn("Invalid migration");

        var body = objectMapper.writeValueAsString(
                new MigrateCategoryRequest(List.of(productId), ProductCategory.PRODUCE, UUID.randomUUID()));
        mockMvc.perform(post("/api/v1/categories/migrate")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized());
    }
}
