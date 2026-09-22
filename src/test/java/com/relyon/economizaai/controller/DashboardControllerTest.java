package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.DashboardResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.dashboard.DashboardService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DashboardService dashboardService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    @Test
    void dashboard_returnsSnapshotForAuthenticatedUser() throws Exception {
        var user = buildUser();
        var snapshot = new DashboardResponse.SpendSnapshot(2026, 6, new BigDecimal("450.30"),
                new BigDecimal("12.50"), 4L, new BigDecimal("112.58"));
        var response = new DashboardResponse(snapshot, List.of(), List.of(), List.of(), 7L, LocalDateTime.now());
        when(dashboardService.build(any(User.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMonth.year").value(2026))
                .andExpect(jsonPath("$.currentMonth.month").value(6))
                .andExpect(jsonPath("$.currentMonth.total").value(450.30))
                .andExpect(jsonPath("$.currentMonth.receiptCount").value(4))
                .andExpect(jsonPath("$.unreadNotificationCount").value(7))
                .andExpect(jsonPath("$.recentReceipts").isEmpty())
                .andExpect(jsonPath("$.suggestedShoppingList").isEmpty())
                .andExpect(jsonPath("$.communityPromosNearby").isEmpty());

        verify(dashboardService).build(user);
    }

    @Test
    void dashboard_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isUnauthorized());
    }
}
