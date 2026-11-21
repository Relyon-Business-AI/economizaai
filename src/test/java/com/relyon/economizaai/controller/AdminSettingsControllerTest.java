package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.exception.InvalidSettingValueException;
import com.relyon.economizaai.exception.SettingNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.settings.RuntimeSetting;
import com.relyon.economizaai.service.settings.RuntimeSettings;
import com.relyon.economizaai.service.settings.RuntimeSettings.SettingState;
import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSettingsController.class)
@Import(SecurityConfig.class)
class AdminSettingsControllerTest {

    private static final String BASE_PATH = "/api/v1/admin/settings";
    private static final String PRICE_WINDOW_KEY = "economizaai.collaborative.lookback-days";
    private static final String PRICE_WINDOW_PATH = BASE_PATH + "/" + PRICE_WINDOW_KEY;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RuntimeSettings runtimeSettings;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User regularUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("member51802@economizaai.app").household(household).build();
    }

    private User adminUser() {
        var admin = regularUser();
        admin.setRole(Role.ADMIN);
        return admin;
    }

    private SettingState priceWindowChangedTo(String value) {
        return new SettingState(RuntimeSetting.PRICE_WINDOW_DAYS, value, "90", true,
                LocalDateTime.of(2026, Month.NOVEMBER, 19, 15, 30), "admin@economizaai.app");
    }

    @Test
    void list_groupsSettingsBySection_withWhatTheAdminNeedsToEditThem() throws Exception {
        when(runtimeSettings.all()).thenReturn(List.of(
                new SettingState(RuntimeSetting.SUBSCRIPTION_ENFORCE, "false", "false", false, null, null),
                priceWindowChangedTo("60")));

        mockMvc.perform(get(BASE_PATH).with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("PLANS"))
                .andExpect(jsonPath("$[0].label").value("Planos e assinatura"))
                .andExpect(jsonPath("$[0].settings[0].key").value("economizaai.subscription.enforce"))
                .andExpect(jsonPath("$[0].settings[0].type").value("BOOLEAN"))
                .andExpect(jsonPath("$[0].settings[0].changed").value(false))
                .andExpect(jsonPath("$[1].key").value("PRICES"))
                .andExpect(jsonPath("$[1].settings[0].key").value(PRICE_WINDOW_KEY))
                .andExpect(jsonPath("$[1].settings[0].label").value("Validade dos preços da comunidade"))
                .andExpect(jsonPath("$[1].settings[0].reflectsOn").value(
                        containsString("melhores mercados")))
                .andExpect(jsonPath("$[1].settings[0].type").value("INTEGER"))
                .andExpect(jsonPath("$[1].settings[0].unit").value("dias"))
                .andExpect(jsonPath("$[1].settings[0].value").value("60"))
                .andExpect(jsonPath("$[1].settings[0].configuredValue").value("90"))
                .andExpect(jsonPath("$[1].settings[0].changed").value(true))
                .andExpect(jsonPath("$[1].settings[0].min").value(7))
                .andExpect(jsonPath("$[1].settings[0].max").value(365))
                .andExpect(jsonPath("$[1].settings[0].appKey").value("priceWindowDays"))
                .andExpect(jsonPath("$[1].settings[0].changedAt").value("2026-11-19T15:30:00"))
                .andExpect(jsonPath("$[1].settings[0].changedBy").value("admin@economizaai.app"));
    }

    @Test
    void list_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get(BASE_PATH).with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());

        verify(runtimeSettings, never()).all();
    }

    @Test
    void list_unauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(get(BASE_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void change_savesTheValueInTheAdminsName() throws Exception {
        var admin = adminUser();
        when(runtimeSettings.change(PRICE_WINDOW_KEY, "60", admin.getEmail())).thenReturn(priceWindowChangedTo("60"));

        mockMvc.perform(put(PRICE_WINDOW_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"60\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("60"))
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void change_valueTheSettingDoesNotAccept_isBadRequest() throws Exception {
        when(runtimeSettings.change(any(), any(), any())).thenThrow(new InvalidSettingValueException(
                "admin.settings.value.out-of-range", "Validade dos preços da comunidade", "7", "365"));

        mockMvc.perform(put(PRICE_WINDOW_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"5000\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void change_withoutAValue_isBadRequest() throws Exception {
        mockMvc.perform(put(PRICE_WINDOW_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(runtimeSettings, never()).change(any(), any(), any());
    }

    @Test
    void change_unknownSetting_isNotFound() throws Exception {
        when(runtimeSettings.change(any(), any(), any())).thenThrow(new SettingNotFoundException("economizaai.nope"));

        mockMvc.perform(put(BASE_PATH + "/economizaai.nope")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void change_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(put(PRICE_WINDOW_PATH)
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"60\"}"))
                .andExpect(status().isForbidden());

        verify(runtimeSettings, never()).change(any(), any(), any());
    }

    @Test
    void restoreConfigured_returnsTheSettingBackAtItsConfiguredValue() throws Exception {
        var admin = adminUser();
        when(runtimeSettings.restoreConfigured(PRICE_WINDOW_KEY, admin.getEmail())).thenReturn(
                new SettingState(RuntimeSetting.PRICE_WINDOW_DAYS, "90", "90", false, null, null));

        mockMvc.perform(delete(PRICE_WINDOW_PATH).with(SecurityMockMvcRequestPostProcessors.user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("90"))
                .andExpect(jsonPath("$.changed").value(false));
    }

    @Test
    void restoreConfigured_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(delete(PRICE_WINDOW_PATH).with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());

        verify(runtimeSettings, never()).restoreConfigured(any(), any());
    }
}
