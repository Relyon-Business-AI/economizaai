package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.NotificationResponse;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.exception.NotificationNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationDestination;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private NotificationInboxService inbox;
    @MockitoBean private NotificationEventService eventService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    private NotificationResponse buildNotification(UUID id) {
        return new NotificationResponse(id, NotificationType.PRICE_DROP, NotificationDestination.PRODUCT,
                NotificationChannel.PUSH, "Preco caiu", "Arroz Tio Joao baixou para R$ 22,90",
                "{\"productId\":\"" + UUID.randomUUID() + "\"}", true,
                LocalDateTime.now(), null, LocalDateTime.now());
    }

    @Test
    void list_returnsPageOfNotifications() throws Exception {
        var user = buildUser();
        var notificationId = UUID.randomUUID();
        Page<NotificationResponse> page = new PageImpl<>(List.of(buildNotification(notificationId)));
        when(inbox.list(any(User.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/notifications")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(notificationId.toString()))
                .andExpect(jsonPath("$.content[0].type").value("PRICE_DROP"))
                .andExpect(jsonPath("$.content[0].destination").value("PRODUCT"))
                .andExpect(jsonPath("$.content[0].title").value("Preco caiu"));
    }

    @Test
    void unreadCount_returnsBadgeCount() throws Exception {
        var user = buildUser();
        when(inbox.unreadCount(any(User.class))).thenReturn(5L);

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(5));
    }

    @Test
    void markRead_returns204AndScopesToAuthUser() throws Exception {
        var user = buildUser();
        var notificationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/notifications/" + notificationId + "/read")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNoContent());

        verify(inbox).markRead(user, notificationId);
    }

    @Test
    void markRead_unknownNotification_returns404() throws Exception {
        var user = buildUser();
        var notificationId = UUID.randomUUID();
        when(localizedMessageService.translate(any(DomainException.class))).thenReturn("not found");
        doThrow(new NotificationNotFoundException()).when(inbox).markRead(user, notificationId);

        mockMvc.perform(post("/api/v1/notifications/" + notificationId + "/read")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void markAllRead_returnsMarkedCount() throws Exception {
        var user = buildUser();
        when(inbox.markAllRead(any(User.class))).thenReturn(3);

        mockMvc.perform(post("/api/v1/notifications/mark-all-read")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(3));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }
}
