package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.CheckoutResponse;
import com.relyon.economizaai.exception.BillingNotConfiguredException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.subscription.MercadoPagoCheckoutService;
import com.relyon.economizaai.service.subscription.MercadoPagoProperties;
import com.relyon.economizaai.service.subscription.SubscriptionUsageService;
import com.relyon.economizaai.dto.response.SubscriptionUsageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionController.class)
@Import(SecurityConfig.class)
class SubscriptionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MercadoPagoCheckoutService checkoutService;
    @MockitoBean private MercadoPagoProperties mercadoPagoProperties;
    @MockitoBean private SubscriptionUsageService usageService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).name("John").email("john@test.com")
                .role(Role.USER).household(household).active(true).build();
    }

    @Test
    void usage_returnsUsageNextToFreeCaps() throws Exception {
        when(usageService.usageOf(any(User.class))).thenReturn(
                new SubscriptionUsageResponse(12, 3, 2, 1, 4, 3, LocalDate.of(2026, 3, 9), 90));

        mockMvc.perform(get("/api/v1/subscriptions/usage")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptsThisMonth").value(12))
                .andExpect(jsonPath("$.freeMonthlyReceipts").value(3))
                .andExpect(jsonPath("$.customCategories").value(2))
                .andExpect(jsonPath("$.freeWatchedMarkets").value(3))
                .andExpect(jsonPath("$.oldestPurchaseDate").value("2026-03-09"))
                .andExpect(jsonPath("$.freeHistoryDays").value(90));
    }

    @Test
    void usage_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions/usage")).andExpect(status().isUnauthorized());
    }

    @Test
    void plan_returnsAmountAndAvailability() throws Exception {
        when(mercadoPagoProperties.getPlanAmount()).thenReturn(new BigDecimal("9.90"));
        when(mercadoPagoProperties.isCheckoutConfigured()).thenReturn(false);

        mockMvc.perform(get("/api/v1/subscriptions/plan")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyAmount").value(9.90))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.webCheckoutAvailable").value(false));
    }

    @Test
    void checkout_returnsUrl() throws Exception {
        when(checkoutService.createCheckout(any(User.class)))
                .thenReturn(new CheckoutResponse("https://mp/init_point"));

        mockMvc.perform(post("/api/v1/subscriptions/checkout")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://mp/init_point"));
    }

    @Test
    void checkout_returns503WhenBillingNotConfigured() throws Exception {
        doThrow(new BillingNotConfiguredException())
                .when(checkoutService).createCheckout(any(User.class));

        mockMvc.perform(post("/api/v1/subscriptions/checkout")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isServiceUnavailable());
    }
}
