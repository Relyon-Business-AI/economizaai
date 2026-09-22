package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizaai.dto.response.PriceAlertResponse;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.exception.PriceAlertNotFoundException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.alerts.PriceAlertService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PriceAlertController.class)
@Import(SecurityConfig.class)
class PriceAlertControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PriceAlertService priceAlertService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    private PriceAlertResponse buildAlert(UUID alertId, UUID productId) {
        return new PriceAlertResponse(alertId, productId, "Arroz Tio Joao", null,
                new BigDecimal("5.99"), 5.0, true, null, LocalDateTime.now());
    }

    @Test
    void create_returns201WithAlert() throws Exception {
        var user = buildUser();
        var productId = UUID.randomUUID();
        var alertId = UUID.randomUUID();
        when(priceAlertService.create(any(User.class), any(CreatePriceAlertRequest.class)))
                .thenReturn(buildAlert(alertId, productId));
        var body = "{\"productId\":\"" + productId + "\",\"thresholdPrice\":5.99,\"radiusKm\":5.0}";

        mockMvc.perform(post("/api/v1/alerts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(alertId.toString()))
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.productName").value("Arroz Tio Joao"))
                .andExpect(jsonPath("$.thresholdPrice").value(5.99))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_missingThresholdPrice_returns400AndDoesNotCallService() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");
        var body = "{\"productId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post("/api/v1/alerts")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.thresholdPrice").exists());

        verify(priceAlertService, never()).create(any(), any());
    }

    @Test
    void create_unknownProduct_returns404() throws Exception {
        when(localizedMessageService.translate(any(DomainException.class))).thenReturn("not found");
        when(priceAlertService.create(any(User.class), any(CreatePriceAlertRequest.class)))
                .thenThrow(new ProductNotFoundException());
        var body = "{\"productId\":\"" + UUID.randomUUID() + "\",\"thresholdPrice\":5.99}";

        mockMvc.perform(post("/api/v1/alerts")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsUserAlerts() throws Exception {
        var user = buildUser();
        var alertId = UUID.randomUUID();
        when(priceAlertService.list(any(User.class))).thenReturn(List.of(buildAlert(alertId, UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/alerts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(alertId.toString()))
                .andExpect(jsonPath("$[0].productName").value("Arroz Tio Joao"));
    }

    @Test
    void delete_returns204() throws Exception {
        var user = buildUser();
        var alertId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/alerts/" + alertId)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNoContent());

        verify(priceAlertService).delete(user, alertId);
    }

    @Test
    void delete_unknownAlert_returns404() throws Exception {
        var user = buildUser();
        var alertId = UUID.randomUUID();
        when(localizedMessageService.translate(any(DomainException.class))).thenReturn("not found");
        doThrow(new PriceAlertNotFoundException()).when(priceAlertService).delete(user, alertId);

        mockMvc.perform(delete("/api/v1/alerts/" + alertId)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized());
    }
}
