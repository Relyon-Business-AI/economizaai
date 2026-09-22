package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.HomeAvailabilityResponse;
import com.relyon.economizaai.dto.response.HomeAvailabilityResponse.FeatureAvailability;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AvailabilityReason;
import com.relyon.economizaai.model.enums.HomeFeature;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.home.HomeAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HomeController.class)
@Import(SecurityConfig.class)
class HomeControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HomeAvailabilityService homeAvailabilityService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    @Test
    void availability_returnsFeatureListForAuthenticatedUser() throws Exception {
        var response = new HomeAvailabilityResponse(List.of(
                new FeatureAvailability(HomeFeature.CONSUMPTION_PREDICTIONS, false,
                        AvailabilityReason.NEEDS_MORE_RECEIPTS, 0, 2),
                new FeatureAvailability(HomeFeature.COMMUNITY_DEALS, false,
                        AvailabilityReason.NEEDS_COMMUNITY, 1, 3)));
        when(homeAvailabilityService.forUser(any(User.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/home/availability")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0].feature").value("CONSUMPTION_PREDICTIONS"))
                .andExpect(jsonPath("$.features[0].available").value(false))
                .andExpect(jsonPath("$.features[0].reason").value("NEEDS_MORE_RECEIPTS"))
                .andExpect(jsonPath("$.features[0].need").value(2))
                .andExpect(jsonPath("$.features[1].reason").value("NEEDS_COMMUNITY"));
    }

    @Test
    void availability_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/home/availability"))
                .andExpect(status().isUnauthorized());
    }
}
