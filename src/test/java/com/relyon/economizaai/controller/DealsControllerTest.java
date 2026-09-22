package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.priceindex.DealsService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealsController.class)
@Import(SecurityConfig.class)
class DealsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DealsService dealsService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    private DealResponse deal() {
        return new DealResponse(UUID.randomUUID(), "Leite", null, "MEAT_DAIRY",
                "12345678000199", "Mercado X",
                new BigDecimal("7.00"), new BigDecimal("10.00"),
                new BigDecimal("3.00"), new BigDecimal("30.00"), new BigDecimal("0.3000"),
                3L, null, true, LocalDateTime.now());
    }

    @Test
    void returnsRankedDealsScopedToUser() throws Exception {
        var user = principal();
        when(dealsService.findDeals(eq(user), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(deal()));

        mockMvc.perform(get("/api/v1/deals")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Leite"))
                .andExpect(jsonPath("$[0].marketCnpj").value("12345678000199"))
                .andExpect(jsonPath("$[0].savingsPct").value(30.00))
                .andExpect(jsonPath("$[0].isWatched").value(true));

        verify(dealsService).findDeals(eq(user), anyBoolean(), any(), anyInt());
    }

    @Test
    void forwardsParams() throws Exception {
        when(dealsService.findDeals(any(), eq(true), eq(5.0), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/deals?includeNearby=true&radiusKm=5.0&limit=50")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk());

        verify(dealsService).findDeals(any(), eq(true), eq(5.0), eq(50));
    }

    @Test
    void nonPositiveLimitReturnsEmptyWithoutThrowing() throws Exception {
        when(dealsService.findDeals(any(), anyBoolean(), any(), eq(0)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/deals?limit=0")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/deals")).andExpect(status().isUnauthorized());
    }
}
