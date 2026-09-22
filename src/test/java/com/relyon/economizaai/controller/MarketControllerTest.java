package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.MarketResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketController.class)
@Import(SecurityConfig.class)
class MarketControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WatchedMarketService watchedMarketService;
    @MockitoBean private MarketNameService marketNameService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void list_returnsCatalogue() throws Exception {
        when(watchedMarketService.listForPicker(any(), any())).thenReturn(List.of(
                new MarketResponse("11111111000111", "11111111", "Mercado A", "Mercado A", "Rua X",
                        null, null, null, true, false)
        ));

        mockMvc.perform(get("/api/v1/markets")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cnpj").value("11111111000111"))
                .andExpect(jsonPath("$[0].visited").value(true));
    }

    @Test
    void watched_returnsOnlyPinned() throws Exception {
        when(watchedMarketService.listWatched(any())).thenReturn(List.of(
                new MarketResponse("22222222000111", "22222222", "Mercado B", "Mercado B", null,
                        null, null, null, false, true)
        ));

        mockMvc.perform(get("/api/v1/markets/watched")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cnpj").value("22222222000111"))
                .andExpect(jsonPath("$[0].watching").value(true));
    }

    @Test
    void pin_returnsRow() throws Exception {
        when(watchedMarketService.watch(any(), eq("33333333000111"))).thenReturn(
                new MarketResponse("33333333000111", "33333333", "Mercado C", "Mercado C", null,
                        null, null, null, false, true));

        mockMvc.perform(post("/api/v1/markets/watched/33333333000111")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(true));
    }

    @Test
    void unpin_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/markets/watched/33333333000111")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNoContent());
        verify(watchedMarketService).unwatch(any(), eq("33333333000111"));
    }

    @Test
    void allEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/markets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/markets/watched")).andExpect(status().isUnauthorized());
    }
}
