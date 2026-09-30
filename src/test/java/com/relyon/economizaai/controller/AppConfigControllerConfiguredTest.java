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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the kill-switch lever pulled (env vars set), the JSON must carry the exact
 * field names the FE's {@code AppRemoteConfig} consumes: minVersion, androidUrl,
 * iosUrl, message.
 */
@WebMvcTest(AppConfigController.class)
@Import({SecurityConfig.class, AppConfigService.class})
@TestPropertySource(properties = {
        "economizaai.app-config.min-version=1.4.0",
        "economizaai.app-config.android-url=market://details?id=economiza.ai",
        "economizaai.app-config.ios-url=itms-apps://apps.apple.com/app/id0000000000",
        "economizaai.app-config.message=Atualize o app para continuar."
})
class AppConfigControllerConfiguredTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void appConfig_exposesConfiguredValuesWithTheGistFieldNames() throws Exception {
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minVersion").value("1.4.0"))
                .andExpect(jsonPath("$.androidUrl").value("market://details?id=economiza.ai"))
                .andExpect(jsonPath("$.iosUrl").value("itms-apps://apps.apple.com/app/id0000000000"))
                .andExpect(jsonPath("$.message").value("Atualize o app para continuar."));
    }
}
