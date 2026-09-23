package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.PriceHistoryResponse;
import com.relyon.economizaai.dto.response.MarketDiscountResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.InsightsQueryService;
import com.relyon.economizaai.service.InsightsService;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightsController.class)
@Import(SecurityConfig.class)
class InsightsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private InsightsService insightsService;
    @MockitoBean private InsightsQueryService insightsQueryService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    @Test
    void spend_returnsAggregatedResponse() throws Exception {
        var user = buildUser();
        var response = new SpendInsightsResponse(
                null, null, new BigDecimal("250.50"), new BigDecimal("5.00"),
                List.of(new SpendInsightsResponse.MonthBucket(2026, 4, new BigDecimal("250.50"), new BigDecimal("5.00"), 3L)),
                List.of(new SpendInsightsResponse.WeekBucket(2026, 17, new BigDecimal("250.50"), new BigDecimal("5.00"), 3L)),
                List.of(new SpendInsightsResponse.MarketBucket("12345678000190", "Mercado X", "Mercado X", new BigDecimal("250.50"), new BigDecimal("5.00"), 3L)),
                List.of(SpendInsightsResponse.CategoryBucket.ofEnum(ProductCategory.GROCERIES, new BigDecimal("100.00"), 5L))
        );
        when(insightsService.spend(any(User.class), any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/insights/spend")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(250.50))
                .andExpect(jsonPath("$.byMonth[0].year").value(2026))
                .andExpect(jsonPath("$.byMarket[0].marketName").value("Mercado X"))
                .andExpect(jsonPath("$.byCategory[0].category").value("GROCERIES"));
    }

    @Test
    void topMarkets_respectsLimit() throws Exception {
        var user = buildUser();
        var bucket = new SpendInsightsResponse.MarketBucket("123", "Mercado X", "Mercado X", new BigDecimal("100"), new BigDecimal("2.00"), 2L);
        when(insightsService.topMarkets(any(User.class), any(), any(), anyInt(), any())).thenReturn(List.of(bucket));

        mockMvc.perform(get("/api/v1/insights/markets/top?limit=3")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].marketName").value("Mercado X"));
    }

    @Test
    void topMarketsByDiscount_returnsRankedList() throws Exception {
        var user = buildUser();
        var market = new MarketDiscountResponse("123", "Mercado X", "Mercado X",
                new BigDecimal("100.00"), new BigDecimal("9.00"), new BigDecimal("0.0900"), 2L);
        when(insightsService.topMarketsByDiscount(any(User.class), any(), any(), anyInt(), any()))
                .thenReturn(List.of(market));

        mockMvc.perform(get("/api/v1/insights/markets/top-discounts?limit=5")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].marketName").value("Mercado X"))
                .andExpect(jsonPath("$[0].discount").value(9.00))
                .andExpect(jsonPath("$[0].discountRate").value(0.09));
    }

    @Test
    void topCategories_returnsList_defaultsToHouseholdLens() throws Exception {
        var user = buildUser();
        var bucket = SpendInsightsResponse.CategoryBucket.ofEnum(ProductCategory.PRODUCE, new BigDecimal("50"), 7L);
        when(insightsService.topCategories(any(User.class), any(), any(), anyInt(), eq(CategoryView.HOUSEHOLD), any()))
                .thenReturn(List.of(bucket));

        mockMvc.perform(get("/api/v1/insights/categories/top")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("PRODUCE"))
                .andExpect(jsonPath("$[0].label").value("PRODUCE"));
    }

    @Test
    void topCategories_passesGlobalLensThrough() throws Exception {
        var user = buildUser();
        var bucket = SpendInsightsResponse.CategoryBucket.ofEnum(ProductCategory.BEVERAGES, new BigDecimal("12"), 3L);
        when(insightsService.topCategories(any(User.class), any(), any(), anyInt(), eq(CategoryView.GLOBAL), any()))
                .thenReturn(List.of(bucket));

        mockMvc.perform(get("/api/v1/insights/categories/top?categoryView=GLOBAL")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("BEVERAGES"));
    }

    @Test
    void unknownInsightsPath_returns404NotInternalError() throws Exception {
        var user = buildUser();

        mockMvc.perform(get("/api/v1/insights/categories")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void priceHistory_returnsPoints() throws Exception {
        var user = buildUser();
        var productId = UUID.randomUUID();
        var response = new PriceHistoryResponse(productId, "Arroz Tio Joao", null,
                List.of(new PriceHistoryResponse.PricePoint(LocalDateTime.now(), "12345678000190",
                        "Mercado X", "Mercado X", new BigDecimal("28.90"), new BigDecimal("2"))));
        when(insightsService.priceHistory(any(User.class), eq(productId), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/insights/products/" + productId + "/price-history")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Arroz Tio Joao"))
                .andExpect(jsonPath("$.points[0].marketName").value("Mercado X"));
    }
}
