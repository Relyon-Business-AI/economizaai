package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.AppConfigService;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppConfigController.class)
@Import({SecurityConfig.class, AppConfigService.class})
class AppConfigControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void appConfig_isPubliclyAccessible_withNoOpDefaultsAndShortCache() throws Exception {
        // No token, no configured values: min-version 0.0.0 blocks nothing and the
        // optional fields are omitted so the FE gate stays a NO-OP.
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60"))
                .andExpect(jsonPath("$.minVersion").value("0.0.0"))
                .andExpect(jsonPath("$.androidUrl").doesNotExist())
                .andExpect(jsonPath("$.iosUrl").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }
}
