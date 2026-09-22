package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.CreateAliasRequest;
import com.relyon.economizaai.dto.request.CreateProductRequest;
import com.relyon.economizaai.dto.response.ProductResponse;
import com.relyon.economizaai.dto.response.UnmatchedItemResponse;
import com.relyon.economizaai.exception.EanConflictException;
import com.relyon.economizaai.exception.ProductAliasConflictException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.HouseholdProductService;
import com.relyon.economizaai.service.ProductRecentViewService;
import com.relyon.economizaai.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private ProductService productService;
    @MockitoBean private HouseholdProductService householdProductService;
    @MockitoBean private ProductRecentViewService recentViewService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    private ProductResponse sampleProduct(UUID id) {
        return new ProductResponse(id, "789", "Arroz Tio Joao", "Arroz", null, "Tio João",
                ProductCategory.GROCERIES, "UN", new BigDecimal("5"), "KG",
                CategorizationSource.DICTIONARY, false);
    }

    @Test
    void search_returnsPagedResults() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        Page<ProductResponse> page = new PageImpl<>(List.of(sampleProduct(id)));
        when(productService.search(eq("arroz"), any(User.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/products?query=arroz")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].normalizedName").value("Arroz Tio Joao"));
    }

    @Test
    void create_returns201() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        var request = new CreateProductRequest("789", "Arroz Tio Joao", null, "Tio Joao", ProductCategory.GROCERIES, "UN", null, null);
        when(productService.create(any(CreateProductRequest.class))).thenReturn(sampleProduct(id));

        mockMvc.perform(post("/api/v1/products")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ean").value("789"));
    }

    @Test
    void create_returns409OnDuplicateEan() throws Exception {
        var user = buildUser();
        var request = new CreateProductRequest("789", "Arroz", null, null, null, null, null, null);
        when(productService.create(any(CreateProductRequest.class))).thenThrow(new EanConflictException("789"));

        mockMvc.perform(post("/api/v1/products")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void unmatched_returnsListForCurrentHousehold() throws Exception {
        var user = buildUser();
        var item = new UnmatchedItemResponse(UUID.randomUUID(), UUID.randomUUID(), "Mercado X",
                LocalDateTime.now(), "ARROZ TIO J", "789", new BigDecimal("28.90"), new BigDecimal("28.90"), "UN");
        when(productService.listUnmatched(any(User.class))).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/products/unmatched")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rawDescription").value("ARROZ TIO J"));
    }

    @Test
    void addAlias_returns201() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(productService.addAlias(any(User.class), eq(id), any(CreateAliasRequest.class)))
                .thenReturn(sampleProduct(id));

        mockMvc.perform(post("/api/v1/products/" + id + "/aliases")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAliasRequest("ARROZ TIO J"))))
                .andExpect(status().isCreated());
    }

    @Test
    void addAlias_returns409OnConflict() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(productService.addAlias(any(User.class), eq(id), any(CreateAliasRequest.class)))
                .thenThrow(new ProductAliasConflictException("ARROZ"));

        mockMvc.perform(post("/api/v1/products/" + id + "/aliases")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAliasRequest("ARROZ"))))
                .andExpect(status().isConflict());
    }

    @Test
    void get_returns404WhenMissing() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(productService.get(eq(id), any(User.class))).thenThrow(new ProductNotFoundException());

        mockMvc.perform(get("/api/v1/products/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound());
    }
}
