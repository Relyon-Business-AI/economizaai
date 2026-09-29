package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.subscription.RevenueCatWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Render placeholder value must behave as "not configured" — otherwise
 * anyone sending Authorization: CHANGEME could forge entitlement events.
 */
@WebMvcTest(RevenueCatWebhookController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "economizaai.billing.revenuecat.auth-header=CHANGEME")
class RevenueCatWebhookControllerPlaceholderTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RevenueCatWebhookService revenueCatWebhookService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void placeholderSecret_rejectsEvenWhenHeaderMatchesIt() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/revenuecat")
                        .header("Authorization", "CHANGEME")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":{\"type\":\"INITIAL_PURCHASE\",\"app_user_id\":\"u@test.com\"}}"))
                .andExpect(status().isUnauthorized());

        verify(revenueCatWebhookService, never()).handle(any());
    }
}
