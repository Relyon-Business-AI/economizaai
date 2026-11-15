package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.SaveAnnouncementRequest;
import com.relyon.economizaai.dto.response.AdminAnnouncementResponse;
import com.relyon.economizaai.exception.AnnouncementNotFoundException;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.exception.InvalidAnnouncementException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.model.enums.AnnouncementStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.admin.AdminAnnouncementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAnnouncementController.class)
@Import(SecurityConfig.class)
class AdminAnnouncementControllerTest {

    private static final String BASE_PATH = "/api/v1/admin/announcements";
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, Month.NOVEMBER, 20, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, Month.NOVEMBER, 30, 18, 30);
    private static final String FULL_BODY = """
            {"kind":"TIP","title":"Compare mercados","body":"Veja onde sua lista sai mais barata.",
             "actionLabel":"Ver mercados","destination":"MARKETS",
             "startsAt":"2026-11-20T09:00:00","endsAt":"2026-11-30T18:30:00","active":true}
            """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminAnnouncementService announcementService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User regularUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("member40917@economizaai.app").household(household).build();
    }

    private User adminUser() {
        var admin = regularUser();
        admin.setRole(Role.ADMIN);
        return admin;
    }

    private AdminAnnouncementResponse sampleAnnouncement(UUID announcementId) {
        return new AdminAnnouncementResponse(announcementId, AnnouncementKind.TIP, "Compare mercados",
                "Veja onde sua lista sai mais barata.", "Ver mercados", AnnouncementDestination.MARKETS,
                STARTS_AT, ENDS_AT, true, AnnouncementStatus.SCHEDULED, 4L,
                LocalDateTime.of(2026, Month.NOVEMBER, 15, 10, 0));
    }

    // --- list ---

    @Test
    void list_returnsAnnouncementsWithStatusAndDismissalCount() throws Exception {
        var announcementId = UUID.randomUUID();
        when(announcementService.list()).thenReturn(List.of(sampleAnnouncement(announcementId)));

        mockMvc.perform(get(BASE_PATH).with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(announcementId.toString()))
                .andExpect(jsonPath("$[0].kind").value("TIP"))
                .andExpect(jsonPath("$[0].title").value("Compare mercados"))
                .andExpect(jsonPath("$[0].body").value("Veja onde sua lista sai mais barata."))
                .andExpect(jsonPath("$[0].actionLabel").value("Ver mercados"))
                .andExpect(jsonPath("$[0].destination").value("MARKETS"))
                .andExpect(jsonPath("$[0].startsAt").value("2026-11-20T09:00:00"))
                .andExpect(jsonPath("$[0].endsAt").value("2026-11-30T18:30:00"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$[0].dismissalCount").value(4))
                .andExpect(jsonPath("$[0].createdAt").value("2026-11-15T10:00:00"));
    }

    @Test
    void list_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get(BASE_PATH).with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());

        verify(announcementService, never()).list();
    }

    @Test
    void list_unauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());
    }

    // --- create ---

    @Test
    void create_returns201AndParsesBrasiliaLocalDateTimes() throws Exception {
        var announcementId = UUID.randomUUID();
        when(announcementService.create(any(SaveAnnouncementRequest.class))).thenReturn(sampleAnnouncement(announcementId));

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(announcementId.toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        var requestCaptor = ArgumentCaptor.forClass(SaveAnnouncementRequest.class);
        verify(announcementService).create(requestCaptor.capture());
        assertEquals(new SaveAnnouncementRequest(AnnouncementKind.TIP, "Compare mercados",
                "Veja onde sua lista sai mais barata.", "Ver mercados", AnnouncementDestination.MARKETS,
                STARTS_AT, ENDS_AT, true), requestCaptor.getValue());
    }

    @Test
    void create_onlyRequiredFields_leavesTheRestNull() throws Exception {
        when(announcementService.create(any(SaveAnnouncementRequest.class))).thenReturn(sampleAnnouncement(UUID.randomUUID()));

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEWS\",\"title\":\"Novidade\"}"))
                .andExpect(status().isCreated());

        var requestCaptor = ArgumentCaptor.forClass(SaveAnnouncementRequest.class);
        verify(announcementService).create(requestCaptor.capture());
        assertNull(requestCaptor.getValue().startsAt());
        assertNull(requestCaptor.getValue().endsAt());
        assertNull(requestCaptor.getValue().active());
    }

    @Test
    void create_missingKindAndTitle_returns400AndDoesNotCallService() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"sem título\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kind").exists())
                .andExpect(jsonPath("$.errors.title").exists());

        verify(announcementService, never()).create(any());
    }

    @Test
    void create_textsOverTheirLimits_returns400() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");
        var body = "{\"kind\":\"NEWS\",\"title\":\"" + "t".repeat(81) + "\",\"body\":\"" + "b".repeat(161)
                + "\",\"actionLabel\":\"" + "a".repeat(31) + "\",\"destination\":\"DEALS\"}";

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.body").exists())
                .andExpect(jsonPath("$.errors.actionLabel").exists());

        verify(announcementService, never()).create(any());
    }

    @Test
    void create_unknownDestination_returns400() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEWS\",\"title\":\"Novidade\",\"actionLabel\":\"Abrir\",\"destination\":\"NOWHERE\"}"))
                .andExpect(status().isBadRequest());

        verify(announcementService, never()).create(any());
    }

    @Test
    void create_actionLabelWithoutDestination_returnsLocalized400() throws Exception {
        when(localizedMessageService.translate(any(DomainException.class)))
                .thenReturn("Informe o texto do botão e o destino juntos, ou deixe os dois em branco.");
        when(announcementService.create(any(SaveAnnouncementRequest.class)))
                .thenThrow(InvalidAnnouncementException.incompleteAction());

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEWS\",\"title\":\"Novidade\",\"actionLabel\":\"Abrir\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Informe o texto do botão e o destino juntos, ou deixe os dois em branco."));
    }

    @Test
    void create_endBeforeStart_returnsLocalized400() throws Exception {
        when(localizedMessageService.translate(any(DomainException.class)))
                .thenReturn("O fim precisa ser depois do início.");
        when(announcementService.create(any(SaveAnnouncementRequest.class)))
                .thenThrow(InvalidAnnouncementException.endsBeforeItStarts());

        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEWS\",\"title\":\"Novidade\","
                                + "\"startsAt\":\"2026-11-20T09:00:00\",\"endsAt\":\"2026-11-19T09:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("O fim precisa ser depois do início."));
    }

    @Test
    void create_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isForbidden());

        verify(announcementService, never()).create(any());
    }

    // --- update ---

    @Test
    void update_returns200WithTheReplacedAnnouncement() throws Exception {
        var announcementId = UUID.randomUUID();
        when(announcementService.update(eq(announcementId), any(SaveAnnouncementRequest.class)))
                .thenReturn(sampleAnnouncement(announcementId));

        mockMvc.perform(put(BASE_PATH + "/" + announcementId)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(announcementId.toString()))
                .andExpect(jsonPath("$.dismissalCount").value(4));
    }

    @Test
    void update_unknownAnnouncement_returns404() throws Exception {
        var announcementId = UUID.randomUUID();
        when(localizedMessageService.translate(any(DomainException.class))).thenReturn("not found");
        when(announcementService.update(eq(announcementId), any(SaveAnnouncementRequest.class)))
                .thenThrow(new AnnouncementNotFoundException());

        mockMvc.perform(put(BASE_PATH + "/" + announcementId)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(put(BASE_PATH + "/" + UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isForbidden());

        verify(announcementService, never()).update(any(), any());
    }

    // --- delete ---

    @Test
    void delete_returns204() throws Exception {
        var announcementId = UUID.randomUUID();

        mockMvc.perform(delete(BASE_PATH + "/" + announcementId)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isNoContent());

        verify(announcementService).delete(announcementId);
    }

    @Test
    void delete_unknownAnnouncement_returns404() throws Exception {
        var announcementId = UUID.randomUUID();
        when(localizedMessageService.translate(any(DomainException.class))).thenReturn("not found");
        doThrow(new AnnouncementNotFoundException()).when(announcementService).delete(announcementId);

        mockMvc.perform(delete(BASE_PATH + "/" + announcementId)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/" + UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());

        verify(announcementService, never()).delete(any());
    }
}
