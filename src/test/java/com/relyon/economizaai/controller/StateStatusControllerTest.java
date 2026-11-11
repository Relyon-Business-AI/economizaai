package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.StateStatusResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.StateHealthStatus;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.service.sefaz.StateStatusService;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/sefaz/state-status} — the in-app FAQ's "situação dos estados". */
@WebMvcTest(SupportedStatesController.class)
@Import(SecurityConfig.class)
class StateStatusControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SefazIngestionService sefazIngestionService;
    @MockitoBean private StateStatusService stateStatusService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("test31877@economizaai.app").household(household).build();
    }

    @Test
    void stateStatus_returnsUfNameAndStatusForEachState() throws Exception {
        when(stateStatusService.currentStatus()).thenReturn(List.of(
                new StateStatusResponse("RS", "Rio Grande do Sul", StateHealthStatus.WORKING),
                new StateStatusResponse("SC", "Santa Catarina", StateHealthStatus.UNSTABLE),
                new StateStatusResponse("SE", "Sergipe", StateHealthStatus.VALIDATING)));

        mockMvc.perform(get("/api/v1/sefaz/state-status")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].uf").value("RS"))
                .andExpect(jsonPath("$[0].name").value("Rio Grande do Sul"))
                .andExpect(jsonPath("$[0].status").value("WORKING"))
                .andExpect(jsonPath("$[1].status").value("UNSTABLE"))
                .andExpect(jsonPath("$[2].status").value("VALIDATING"));
    }

    @Test
    void stateStatus_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/sefaz/state-status"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(stateStatusService);
    }
}
