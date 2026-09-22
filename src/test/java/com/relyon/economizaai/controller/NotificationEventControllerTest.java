package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import com.relyon.economizaai.service.notifications.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationEventControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private NotificationInboxService inbox;
    @MockitoBean private NotificationEventService eventService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void recordEvent_withClientReportableType_returns202AndScopesToAuthUser() throws Exception {
        var user = principal();
        var body = "{\"type\":\"DEAL_TAPPED\",\"productId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post("/api/v1/notifications/events")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        verify(eventService).record(eq(user), eq(NotificationEventType.DEAL_TAPPED), any(RecordContext.class));
    }

    @Test
    void recordEvent_withServerOnlyType_returns400AndDoesNotRecord() throws Exception {
        when(localizedMessageService.translate(any(DomainException.class)))
                .thenReturn("invalid");
        var body = "{\"type\":\"SENT\"}";

        mockMvc.perform(post("/api/v1/notifications/events")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(eventService, never()).record(any(), any(), any());
    }

    @Test
    void recordEvent_withConvertedServerOnlyType_returns400() throws Exception {
        when(localizedMessageService.translate(any(DomainException.class)))
                .thenReturn("invalid");
        var body = "{\"type\":\"CONVERTED\"}";

        mockMvc.perform(post("/api/v1/notifications/events")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordEvent_withUnknownType_returns400() throws Exception {
        when(localizedMessageService.translate(any(DomainException.class)))
                .thenReturn("invalid");
        var body = "{\"type\":\"GARBAGE\"}";

        mockMvc.perform(post("/api/v1/notifications/events")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordEvent_unauthenticated_returns401() throws Exception {
        var body = "{\"type\":\"DEAL_TAPPED\"}";

        mockMvc.perform(post("/api/v1/notifications/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
