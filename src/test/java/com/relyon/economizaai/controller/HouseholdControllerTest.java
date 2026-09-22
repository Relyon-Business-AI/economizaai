package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.HouseholdService;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HouseholdController.class)
@Import(SecurityConfig.class)
class HouseholdControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private HouseholdService householdService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private HouseholdResponse sampleResponse() {
        return new HouseholdResponse(
                UUID.randomUUID(),
                "ABC123",
                LocalDateTime.now().plusHours(48),
                List.of(new HouseholdResponse.HouseholdMember(UUID.randomUUID(), "John", "john@test.com")),
                LocalDateTime.now()
        );
    }

    @Test
    void getMine_returnsHouseholdForAuthenticatedUser() throws Exception {
        var user = buildUser();
        when(householdService.getMine(any(User.class))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/households/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("ABC123"))
                .andExpect(jsonPath("$.members[0].email").value("john@test.com"));
    }

    @Test
    void getMine_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/households/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void join_returnsUpdatedHousehold() throws Exception {
        var user = buildUser();
        when(householdService.join(any(User.class), any(JoinHouseholdRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/households/join")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinHouseholdRequest("ABC123", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("ABC123"));
    }

    @Test
    void join_returns400ForInvalidCode() throws Exception {
        var user = buildUser();
        when(householdService.join(any(User.class), any(JoinHouseholdRequest.class)))
                .thenThrow(new InvalidInviteCodeException("XYZ999"));

        mockMvc.perform(post("/api/v1/households/join")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinHouseholdRequest("XYZ999", null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leave_returnsFreshHousehold() throws Exception {
        var user = buildUser();
        when(householdService.leave(any(User.class))).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/households/leave")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("ABC123"));
    }
}
