package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.legal.LegalDocuments;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({LegalController.class, LegalDocuments.class})
@Import(SecurityConfig.class)
class LegalControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void terms_isPubliclyAccessible_andReturnsCurrentVersion() throws Exception {
        mockMvc.perform(get("/api/v1/legal/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(LegalDocuments.CURRENT_TERMS_VERSION))
                .andExpect(jsonPath("$.content").exists());
    }

    @Test
    void privacy_isPubliclyAccessible_andReturnsCurrentVersion() throws Exception {
        mockMvc.perform(get("/api/v1/legal/privacy-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(LegalDocuments.CURRENT_PRIVACY_VERSION))
                .andExpect(jsonPath("$.content").exists());
    }

    @Test
    void privacy_shortPathAlias_isPubliclyAccessible_andReturnsCurrentVersion() throws Exception {
        mockMvc.perform(get("/api/v1/legal/privacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(LegalDocuments.CURRENT_PRIVACY_VERSION))
                .andExpect(jsonPath("$.content").exists());
    }
}
