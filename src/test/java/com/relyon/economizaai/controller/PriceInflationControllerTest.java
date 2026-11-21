package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.PriceInflationRecomputeResponse;
import com.relyon.economizaai.dto.response.PriceInflationResponse;
import com.relyon.economizaai.dto.response.PriceInflationResponse.Basket;
import com.relyon.economizaai.dto.response.PriceInflationResponse.CategoryChange;
import com.relyon.economizaai.dto.response.PriceInflationResponse.Community;
import com.relyon.economizaai.dto.response.PriceInflationResponse.FoodChange;
import com.relyon.economizaai.dto.response.PriceInflationResponse.Official;
import com.relyon.economizaai.dto.response.PriceInflationResponse.OfficialGroup;
import com.relyon.economizaai.dto.response.PriceInflationResponse.SeriesPoint;
import com.relyon.economizaai.dto.response.PriceInflationStateResponse;
import com.relyon.economizaai.exception.PriceInflationUnavailableException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PriceInflationScope;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.inflation.PriceInflationQueryService;
import com.relyon.economizaai.service.inflation.PriceInflationRefreshJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PriceInflationController.class, AdminPriceInflationController.class})
@Import(SecurityConfig.class)
class PriceInflationControllerTest {

    private static final String BASE_PATH = "/api/v1/insights/price-inflation";
    private static final String RECOMPUTE_PATH = "/api/v1/admin/price-inflation/recompute";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PriceInflationQueryService queryService;
    @MockitoBean private PriceInflationRefreshJob refreshJob;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User regularUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("member73110@economizaai.app").household(household).build();
    }

    private User adminUser() {
        var admin = regularUser();
        admin.setRole(Role.ADMIN);
        return admin;
    }

    private static PriceInflationResponse rsOverview() {
        var series = List.of(new SeriesPoint("2026-05", new BigDecimal("100.00")),
                new SeriesPoint("2026-06", new BigDecimal("100.40")));
        return new PriceInflationResponse("STATE", "RS", "Rio Grande do Sul", "RS", "Rio Grande do Sul", 12,
                "2026-06", "2026-08", true,
                new Official("Porto Alegre - RS", false, new BigDecimal("1.2"), series),
                new Community("OK", new BigDecimal("-0.4"), 74, 6, series),
                new Basket("INSUFFICIENT", null, 2, List.of()),
                new BigDecimal("-1.6"),
                List.of(new CategoryChange("carnes", "Carnes", new BigDecimal("7.4"), 16)),
                List.of(new FoodChange("Café", new BigDecimal("18.2"), 5)),
                List.of(new OfficialGroup("7172", "Cereais, leguminosas e oleaginosas", new BigDecimal("-3.1"))));
    }

    @Test
    void overview_answersTheContractShape() throws Exception {
        when(queryService.overview(any(), eq(PriceInflationScope.STATE), eq(UnidadeFederativa.RS), eq(12)))
                .thenReturn(rsOverview());

        mockMvc.perform(get(BASE_PATH).param("scope", "STATE").param("state", "RS").param("months", "12")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("STATE"))
                .andExpect(jsonPath("$.state").value("RS"))
                .andExpect(jsonPath("$.stateName").value("Rio Grande do Sul"))
                .andExpect(jsonPath("$.viewerState").value("RS"))
                .andExpect(jsonPath("$.viewerStateName").value("Rio Grande do Sul"))
                .andExpect(jsonPath("$.requestedMonths").value(12))
                .andExpect(jsonPath("$.from").value("2026-06"))
                .andExpect(jsonPath("$.to").value("2026-08"))
                .andExpect(jsonPath("$.shortened").value(true))
                .andExpect(jsonPath("$.official.areaName").value("Porto Alegre - RS"))
                .andExpect(jsonPath("$.official.nationalFallback").value(false))
                .andExpect(jsonPath("$.official.changePercent").value(1.2))
                .andExpect(jsonPath("$.official.series[0].month").value("2026-05"))
                .andExpect(jsonPath("$.official.series[0].index").value(100.0))
                .andExpect(jsonPath("$.community.status").value("OK"))
                .andExpect(jsonPath("$.community.changePercent").value(-0.4))
                .andExpect(jsonPath("$.community.pairCount").value(74))
                .andExpect(jsonPath("$.community.storeCount").value(6))
                .andExpect(jsonPath("$.basket.status").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.basket.changePercent").doesNotExist())
                .andExpect(jsonPath("$.basket.productCount").value(2))
                .andExpect(jsonPath("$.basket.series").isEmpty())
                .andExpect(jsonPath("$.differencePoints").value(-1.6))
                .andExpect(jsonPath("$.categories[0].slug").value("carnes"))
                .andExpect(jsonPath("$.categories[0].name").value("Carnes"))
                .andExpect(jsonPath("$.categories[0].changePercent").value(7.4))
                .andExpect(jsonPath("$.categories[0].pairCount").value(16))
                .andExpect(jsonPath("$.foods[0].name").value("Café"))
                .andExpect(jsonPath("$.officialGroups[0].code").value("7172"))
                .andExpect(jsonPath("$.officialGroups[0].changePercent").value(-3.1));
    }

    @Test
    void overview_defaultsToBrasilAndLetsTheServicePickThePeriod() throws Exception {
        when(queryService.overview(any(), eq(PriceInflationScope.BR), eq(null), eq(null))).thenReturn(rsOverview());

        mockMvc.perform(get(BASE_PATH).with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isOk());
    }

    @Test
    void whileTheScreenIsKeptToAdmins_anyoneElseGetsALocalized404() throws Exception {
        when(queryService.overview(any(), any(), any(), any())).thenThrow(new PriceInflationUnavailableException());
        when(queryService.states(any(), any())).thenThrow(new PriceInflationUnavailableException());
        when(localizedMessageService.translate(any(PriceInflationUnavailableException.class)))
                .thenReturn("Esta tela ainda não está disponível.");

        mockMvc.perform(get(BASE_PATH).with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Esta tela ainda não está disponível."));
        mockMvc.perform(get(BASE_PATH + "/states").with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    void overview_unauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(get(BASE_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE_PATH + "/states")).andExpect(status().isUnauthorized());
    }

    @Test
    void states_listsOneLinePerState() throws Exception {
        when(queryService.states(any(), eq(12))).thenReturn(List.of(new PriceInflationStateResponse("RS",
                "Rio Grande do Sul", "Porto Alegre - RS", false, new BigDecimal("1.2"), "OK", new BigDecimal("-0.4"),
                new BigDecimal("-1.6"), "2026-06", "2026-08")));

        mockMvc.perform(get(BASE_PATH + "/states").param("months", "12")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("RS"))
                .andExpect(jsonPath("$[0].stateName").value("Rio Grande do Sul"))
                .andExpect(jsonPath("$[0].officialAreaName").value("Porto Alegre - RS"))
                .andExpect(jsonPath("$[0].nationalFallback").value(false))
                .andExpect(jsonPath("$[0].officialChangePercent").value(1.2))
                .andExpect(jsonPath("$[0].communityStatus").value("OK"))
                .andExpect(jsonPath("$[0].communityChangePercent").value(-0.4))
                .andExpect(jsonPath("$[0].differencePoints").value(-1.6))
                .andExpect(jsonPath("$[0].from").value("2026-06"))
                .andExpect(jsonPath("$[0].to").value("2026-08"));
    }

    @Test
    void recompute_isForAdminsOnly() throws Exception {
        when(refreshJob.refreshOnDemand(24)).thenReturn(new PriceInflationRecomputeResponse(24, 9792, 9792, "2026-10", 24, 310, 1250));

        mockMvc.perform(post(RECOMPUTE_PATH).param("months", "24")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months").value(24))
                .andExpect(jsonPath("$.officialReadingsFetched").value(9792))
                .andExpect(jsonPath("$.officialRowsStored").value(9792))
                .andExpect(jsonPath("$.newestOfficialMonth").value("2026-10"))
                .andExpect(jsonPath("$.communityMonthsRecomputed").value(24))
                .andExpect(jsonPath("$.communityProductRows").value(310))
                .andExpect(jsonPath("$.communityPairs").value(1250));

        mockMvc.perform(post(RECOMPUTE_PATH).with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(RECOMPUTE_PATH)).andExpect(status().isUnauthorized());
        verify(refreshJob, never()).refreshOnDemand(null);
    }
}
