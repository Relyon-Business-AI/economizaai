package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BetaSignupController.class)
@Import(SecurityConfig.class)
class BetaSignupControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ContactService contactService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void submit_validSignup_accepted_noAuthNeeded() throws Exception {
        var body = """
                {"name":"Jane Beta","email":"jane@test.com"}
                """;

        mockMvc.perform(post("/api/v1/beta-signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        verify(contactService).submitBetaSignup(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submit_withPhone_accepted() throws Exception {
        var body = """
                {"name":"Jane Beta","email":"jane@test.com","phone":"+55 51 99999-0000"}
                """;

        mockMvc.perform(post("/api/v1/beta-signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        verify(contactService).submitBetaSignup(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submit_phoneTooLong_rejected() throws Exception {
        var body = """
                {"name":"Jane Beta","email":"jane@test.com","phone":"1234567890123456789012345678901"}
                """;

        mockMvc.perform(post("/api/v1/beta-signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).submitBetaSignup(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submit_blankName_rejected() throws Exception {
        var body = """
                {"name":"","email":"jane@test.com"}
                """;

        mockMvc.perform(post("/api/v1/beta-signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).submitBetaSignup(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submit_invalidEmail_rejected() throws Exception {
        var body = """
                {"name":"Jane","email":"not-an-email"}
                """;

        mockMvc.perform(post("/api/v1/beta-signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
