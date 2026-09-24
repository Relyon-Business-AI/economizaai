package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PriceIndexController.class)
@Import(SecurityConfig.class)
class PriceIndexControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PriceIndexService priceIndexService;
    @MockitoBean private CommunityPromoService communityPromoService;
    @MockitoBean private WatchedMarketService watchedMarketService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void reference_returnsBody() throws Exception {
        var pid = UUID.randomUUID();
        var ref = new PriceIndexService.ReferencePrice(
                new BigDecimal("11.00"), new BigDecimal("9.00"), new BigDecimal("13.00"),
                5, 3, LocalDateTime.now(), false);
        when(priceIndexService.referencePrice(any(), any())).thenReturn(ref);

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/markets/93015006005182/reference")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medianPrice").value(11.00))
                .andExpect(jsonPath("$.distinctHouseholds").value(3));
    }

    @Test
    void reference_returnsEmptyShapeWhenSparse() throws Exception {
        var pid = UUID.randomUUID();
        when(priceIndexService.referencePrice(any(), any()))
                .thenReturn(PriceIndexService.ReferencePrice.empty());

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/markets/000/reference")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medianPrice").doesNotExist())
                .andExpect(jsonPath("$.sampleCount").value(0));
    }

    @Test
    void bestMarkets_returnsRanking() throws Exception {
        var pid = UUID.randomUUID();
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(any(), anyInt(), isNull(), isNull(), isNull(), any()))
                .thenReturn(List.of(
                        new PriceIndexService.MarketPriceRow("93015006005182", "93015006", "Mercado X",
                                new BigDecimal("10"), new BigDecimal("9"),
                                new BigDecimal("9"), LocalDateTime.of(2026, 1, 1, 0, 0), 5, 3L, null, false)
                ));

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/best-markets")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cnpj").value("93015006005182"))
                .andExpect(jsonPath("$[0].cnpjRoot").value("93015006"))
                .andExpect(jsonPath("$[0].watching").value(false));
    }

    @Test
    void bestMarkets_usesExplicitPositionWhenProvided() throws Exception {
        var pid = UUID.randomUUID();
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(any(), anyInt(),
                eq(new BigDecimal("-30.05")), eq(new BigDecimal("-51.20")), eq(5.0), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/best-markets")
                        .param("lat", "-30.05").param("lng", "-51.20").param("radiusKm", "5")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk());

        verify(priceIndexService).bestMarkets(any(), anyInt(),
                eq(new BigDecimal("-30.05")), eq(new BigDecimal("-51.20")), eq(5.0), any());
    }

    @Test
    void promos_returnsCurrentPromos() throws Exception {
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(communityPromoService.detectAll(isNull(), isNull(), isNull(), any()))
                .thenReturn(List.of(
                        new CommunityPromoService.CommunityPromo(UUID.randomUUID(), "Arroz",
                                "93015006005182", "93015006", "Mercado X", "Mercado X",
                                new BigDecimal("22"), new BigDecimal("28"), new BigDecimal("21.43"),
                                5, 3L, null, false)
                ));

        mockMvc.perform(get("/api/v1/price-index/promos")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Arroz"))
                .andExpect(jsonPath("$[0].dropPct").value(21.43));
    }

    @Test
    void allEndpointsRequireAuth() throws Exception {
        var pid = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/price-index/promos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/best-markets")).andExpect(status().isUnauthorized());
    }
}
