package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.OptimizeShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.shopping.ShoppingListOptimizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShoppingListOptimizerController.class)
@Import(SecurityConfig.class)
class ShoppingListOptimizerControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ShoppingListOptimizer optimizer;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    @Test
    void optimize_returnsPlanGroupedByMarket() throws Exception {
        var user = buildUser();
        var productId = UUID.randomUUID();
        var planItem = new ShoppingPlanResponse.PlanItem(productId, "Arroz Tio Joao", null,
                new BigDecimal("2"), new BigDecimal("22.90"), new BigDecimal("45.80"),
                ShoppingPlanResponse.PlanItem.PriceSource.LOCAL_HISTORY);
        var marketPlan = new ShoppingPlanResponse.MarketPlan("12345678000190", "Mercado X", "Mercado X",
                new BigDecimal("45.80"), 1, List.of(planItem));
        var unpricedId = UUID.randomUUID();
        var unpriced = new ShoppingPlanResponse.UnpricedItem(unpricedId, "Sabao em po", null,
                BigDecimal.ONE, "no price data");
        var response = new ShoppingPlanResponse(List.of(marketPlan), new BigDecimal("45.80"), List.of(unpriced));
        when(optimizer.optimize(any(User.class), any(OptimizeShoppingListRequest.class))).thenReturn(response);

        var body = "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":2}," +
                "{\"productId\":\"" + unpricedId + "\",\"quantity\":1}]}";

        mockMvc.perform(post("/api/v1/shopping-list/optimize")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedTotal").value(45.80))
                .andExpect(jsonPath("$.marketPlans[0].marketCnpj").value("12345678000190"))
                .andExpect(jsonPath("$.marketPlans[0].items[0].productName").value("Arroz Tio Joao"))
                .andExpect(jsonPath("$.marketPlans[0].items[0].priceSource").value("LOCAL_HISTORY"))
                .andExpect(jsonPath("$.unpriced[0].productName").value("Sabao em po"));
    }

    @Test
    void optimize_emptyItems_returns400AndDoesNotCallService() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");
        var body = "{\"items\":[]}";

        mockMvc.perform(post("/api/v1/shopping-list/optimize")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(optimizer, never()).optimize(any(), any());
    }

    @Test
    void optimize_nonPositiveQuantity_returns400() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");
        var body = "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":0}]}";

        mockMvc.perform(post("/api/v1/shopping-list/optimize")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(optimizer, never()).optimize(any(), any());
    }

    @Test
    void optimize_unauthenticated_returns401() throws Exception {
        var body = "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}";

        mockMvc.perform(post("/api/v1/shopping-list/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
