package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.AnnouncementResponse;
import com.relyon.economizaai.exception.AnnouncementNotFoundException;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.announcement.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnnouncementController.class)
@Import(SecurityConfig.class)
class AnnouncementControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AnnouncementService announcementService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("reader58120@economizaai.app").household(household).build();
    }

    @Test
    void current_returnsTheAnnouncementCard() throws Exception {
        var announcementId = UUID.randomUUID();
        when(announcementService.currentFor(any(User.class))).thenReturn(Optional.of(new AnnouncementResponse(
                announcementId, AnnouncementKind.NEWS, "Compare mercados", "Veja onde sua lista sai mais barata.",
                "Ver mercados", AnnouncementDestination.MARKETS)));

        mockMvc.perform(get("/api/v1/announcements/current")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(announcementId.toString()))
                .andExpect(jsonPath("$.kind").value("NEWS"))
                .andExpect(jsonPath("$.title").value("Compare mercados"))
                .andExpect(jsonPath("$.body").value("Veja onde sua lista sai mais barata."))
                .andExpect(jsonPath("$.actionLabel").value("Ver mercados"))
                .andExpect(jsonPath("$.destination").value("MARKETS"));
    }

    @Test
    void current_withoutBodyOrAction_returnsNullFields() throws Exception {
        when(announcementService.currentFor(any(User.class))).thenReturn(Optional.of(new AnnouncementResponse(
                UUID.randomUUID(), AnnouncementKind.NOTICE, "Manutenção no domingo", null, null, null)));

        mockMvc.perform(get("/api/v1/announcements/current")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NOTICE"))
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(jsonPath("$.actionLabel").doesNotExist())
                .andExpect(jsonPath("$.destination").doesNotExist());
    }

    @Test
    void current_nothingToShow_returns204WithoutBody() throws Exception {
        when(announcementService.currentFor(any(User.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/announcements/current")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void current_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/announcements/current"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dismiss_returns204() throws Exception {
        var user = buildUser();
        var announcementId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/dismissal")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNoContent());

        verify(announcementService).dismiss(user, announcementId);
    }

    @Test
    void dismiss_unknownAnnouncement_returnsLocalized404() throws Exception {
        var user = buildUser();
        var announcementId = UUID.randomUUID();
        when(localizedMessageService.translate(any(DomainException.class)))
                .thenReturn("Esta novidade não está mais disponível.");
        doThrow(new AnnouncementNotFoundException()).when(announcementService).dismiss(user, announcementId);

        mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/dismissal")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Esta novidade não está mais disponível."));
    }

    @Test
    void dismiss_malformedId_returns400() throws Exception {
        when(localizedMessageService.translate("validation.failed")).thenReturn("validation failed");

        mockMvc.perform(post("/api/v1/announcements/not-a-uuid/dismissal")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dismiss_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/announcements/" + UUID.randomUUID() + "/dismissal"))
                .andExpect(status().isUnauthorized());
    }
}
