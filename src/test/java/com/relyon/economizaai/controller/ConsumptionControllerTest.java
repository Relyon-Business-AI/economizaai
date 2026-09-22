package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsumptionController.class)
@Import(SecurityConfig.class)
class ConsumptionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ConsumptionIntelligenceService service;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void predictions_returnsList() throws Exception {
        var pid = UUID.randomUUID();
        when(service.predict(any())).thenReturn(List.of(
                new ConsumptionPredictionResponse(pid, "Leite", "Leite Integral", null,
                        LocalDate.now().minusDays(7), LocalDate.now(), 0L,
                        new BigDecimal("7.0"), new BigDecimal("1.000"), 4,
                        ConsumptionPredictionResponse.Confidence.LOW,
                        ConsumptionPredictionResponse.Status.RUNNING_LOW)
        ));

        mockMvc.perform(get("/api/v1/consumption/predictions")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Leite"))
                .andExpect(jsonPath("$[0].status").value("RUNNING_LOW"));
    }

    @Test
    void suggested_returnsListWithMetadata() throws Exception {
        when(service.suggestedList(any(), anyBoolean(), anyInt())).thenReturn(new SuggestedShoppingListResponse(
                List.of(), LocalDateTime.now()));

        mockMvc.perform(get("/api/v1/consumption/suggested-list")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void requireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/consumption/predictions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/consumption/suggested-list")).andExpect(status().isUnauthorized());
    }
}
