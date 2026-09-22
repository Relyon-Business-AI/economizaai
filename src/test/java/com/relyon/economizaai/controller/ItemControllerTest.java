package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.PurchasedItemResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.CustomCategoryService;
import com.relyon.economizaai.service.ItemQueryService;
import com.relyon.economizaai.service.ItemQueryService.ItemFilters;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ItemController.class)
@Import(SecurityConfig.class)
class ItemControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ItemQueryService itemQueryService;
    @MockitoBean private CustomCategoryService customCategoryService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    private PurchasedItemResponse sampleItem() {
        return new PurchasedItemResponse(
                UUID.randomUUID(), UUID.randomUUID(), "MEAT_DAIRY", "MEAT_DAIRY",
                "Leite Integral", "LEITE INT 1L", "Leite Integral", "7891234567890",
                new BigDecimal("2.000"), "UN", new BigDecimal("4.50"), new BigDecimal("9.00"),
                null, null, false, false, UUID.randomUUID(), "Mercado Bom Preco", "Mercado Bom Preco", "93015006005182", LocalDateTime.now());
    }

    @Test
    void list_returnsPagedItems() throws Exception {
        var page = new PageImpl<>(List.of(sampleItem()), Pageable.ofSize(20), 1);
        when(itemQueryService.query(any(User.class), any(ItemFilters.class), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/items")
                        .param("category", "MEAT_DAIRY")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].displayDescription").value("Leite Integral"))
                .andExpect(jsonPath("$.content[0].category").value("MEAT_DAIRY"))
                .andExpect(jsonPath("$.content[0].marketName").value("Mercado Bom Preco"));
    }

    @Test
    void list_withCustomCategory_resolvesProductIdsAndQueries() throws Exception {
        var customCategoryId = UUID.randomUUID();
        var resolvedProductId = UUID.randomUUID();
        when(customCategoryService.productIdsIn(any(User.class), eq(customCategoryId)))
                .thenReturn(List.of(resolvedProductId));
        when(itemQueryService.query(any(User.class), any(ItemFilters.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleItem()), Pageable.ofSize(20), 1));

        mockMvc.perform(get("/api/v1/items")
                        .param("customCategoryId", customCategoryId.toString())
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(itemQueryService).query(any(User.class), any(ItemFilters.class), any(Pageable.class));
    }

    @Test
    void list_withEmptyCustomCategory_returnsEmptyWithoutQuery() throws Exception {
        var customCategoryId = UUID.randomUUID();
        when(customCategoryService.productIdsIn(any(User.class), eq(customCategoryId)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/items")
                        .param("customCategoryId", customCategoryId.toString())
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        verify(itemQueryService, never()).query(any(User.class), any(ItemFilters.class), any(Pageable.class));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_invalidCategoryEnum_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/items")
                        .param("category", "BOGUS")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());

        verify(itemQueryService, never()).query(any(User.class), any(ItemFilters.class), any(Pageable.class));
    }

    @Test
    void list_passesPaginationAndFilters() throws Exception {
        Page<PurchasedItemResponse> empty = new PageImpl<>(List.of(), Pageable.ofSize(5), 0);
        when(itemQueryService.query(any(User.class), any(ItemFilters.class), any(Pageable.class)))
                .thenReturn(empty);

        mockMvc.perform(get("/api/v1/items")
                        .param("marketCnpj", "93015006005182")
                        .param("ean", "7891234567890")
                        .param("minReceiptTotal", "10.00")
                        .param("size", "5")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(itemQueryService).query(any(User.class), any(ItemFilters.class), any(Pageable.class));
    }
}
