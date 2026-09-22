package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.BrandStrength;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.Confidence;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.preferences.HouseholdPreferenceService;
import com.relyon.economizaai.service.preferences.ManualBrandPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreferenceController.class)
@Import(SecurityConfig.class)
class PreferenceControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HouseholdPreferenceService preferenceService;
    @MockitoBean private ManualBrandPreferenceService manualBrandPreferenceService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void returnsPreferencesList() throws Exception {
        when(preferenceService.derivePreferences(any())).thenReturn(List.of(
                new HouseholdPreferenceResponse(
                        "Leite",
                        new BigDecimal("1"), "L",
                        new BigDecimal("1"), new BigDecimal("2"),
                        "Italac", BrandStrength.MUST_HAVE,
                        new BigDecimal("0.90"), List.of(),
                        10, Confidence.MEDIUM)
        ));

        mockMvc.perform(get("/api/v1/preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].genericName").value("Leite"))
                .andExpect(jsonPath("$[0].topBrand").value("Italac"))
                .andExpect(jsonPath("$[0].brandStrength").value("MUST_HAVE"));
    }

    @Test
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/preferences")).andExpect(status().isUnauthorized());
    }
}
