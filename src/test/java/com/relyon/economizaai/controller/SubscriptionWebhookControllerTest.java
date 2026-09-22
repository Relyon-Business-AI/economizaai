package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionWebhookController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "economizaai.billing.webhook-secret=topsecret")
class SubscriptionWebhookControllerTest {

    private static final String BODY_ACTIVATE = """
            {"userEmail":"u@test.com","action":"ACTIVATE","provider":"stripe",
             "providerRef":"sub_1","currentPeriodEnd":"2026-12-31T00:00:00"}""";
    private static final String BODY_CANCEL =
            "{\"userEmail\":\"u@test.com\",\"action\":\"CANCEL\"}";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SubscriptionService subscriptionService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User user() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@test.com").household(household).build();
    }

    @Test
    void activate_withCorrectSecret_callsActivatePro() throws Exception {
        var user = user();
        when(userRepository.findByEmail("u@test.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .header("X-Webhook-Secret", "topsecret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_ACTIVATE))
                .andExpect(status().isOk());

        verify(subscriptionService).activatePro(user, "stripe", "sub_1",
                LocalDateTime.of(2026, Month.DECEMBER, 31, 0, 0));
    }

    @Test
    void cancel_withCorrectSecret_callsCancel() throws Exception {
        var user = user();
        when(userRepository.findByEmail("u@test.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .header("X-Webhook-Secret", "topsecret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_CANCEL))
                .andExpect(status().isOk());

        verify(subscriptionService).cancel(user);
    }

    @Test
    void wrongSecret_returns401AndDoesNotTouchService() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .header("X-Webhook-Secret", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_ACTIVATE))
                .andExpect(status().isUnauthorized());

        verify(subscriptionService, never()).activatePro(any(), any(), any(), any());
    }

    @Test
    void missingSecret_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_ACTIVATE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUser_isNoOp200_doesNotTouchService() throws Exception {
        when(userRepository.findByEmail("u@test.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .header("X-Webhook-Secret", "topsecret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_ACTIVATE))
                .andExpect(status().isOk());

        verify(subscriptionService, never()).activatePro(any(), any(), any(), any());
        verify(subscriptionService, never()).cancel(any());
    }

    @Test
    void invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .header("X-Webhook-Secret", "topsecret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"not-an-email\",\"action\":\"CANCEL\"}"))
                .andExpect(status().isBadRequest());
    }
}
