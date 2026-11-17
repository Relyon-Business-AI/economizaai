package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.MarketProductPriceResponse;
import com.relyon.economizaai.dto.response.MarketResponse;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.exposure.PriceExposureService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.MarketPricesService;
import com.relyon.economizaai.service.priceindex.MarketPricesSort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    @MockitoBean private MarketPricesService marketPricesService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private PriceExposureService priceExposureService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void list_returnsCatalogue() throws Exception {
        when(watchedMarketService.listMarkets(any(), any(), any(), any())).thenReturn(List.of(
                new MarketResponse("11111111000111", "11111111", "Mercado A", "Mercado A", "Rua X",
                        "Porto Alegre", "RS", null, null, 2.5, true, false, null, null)
        ));

        mockMvc.perform(get("/api/v1/markets")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cnpj").value("11111111000111"))
                .andExpect(jsonPath("$[0].city").value("Porto Alegre"))
                .andExpect(jsonPath("$[0].distanceKm").value(2.5))
                .andExpect(jsonPath("$[0].visited").value(true));
    }

    @Test
    void list_mineScopeAndDevicePositionForwarded() throws Exception {
        when(watchedMarketService.listMarkets(any(), eq(WatchedMarketService.MarketScope.MINE),
                eq(new java.math.BigDecimal("-30.05")), eq(new java.math.BigDecimal("-51.22"))))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/markets")
                        .param("scope", "MINE").param("lat", "-30.05").param("lng", "-51.22")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk());

        verify(watchedMarketService).listMarkets(any(), eq(WatchedMarketService.MarketScope.MINE),
                eq(new java.math.BigDecimal("-30.05")), eq(new java.math.BigDecimal("-51.22")));
    }

    @Test
    void watched_returnsOnlyPinned() throws Exception {
        when(watchedMarketService.listWatched(any())).thenReturn(List.of(
                new MarketResponse("22222222000111", "22222222", "Mercado B", "Mercado B", null,
                        null, null, null, null, null, false, true, null, null)
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
                        null, null, null, null, null, false, true, null, null));

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
    void prices_returnsPagedEnvelopeWithOnlyTheDisclosableFields() throws Exception {
        var productId = UUID.randomUUID();
        var row = new MarketProductPriceResponse(productId, "Arroz Branco 5kg", ProductCategory.GROCERIES,
                new BigDecimal("24.90"), LocalDate.of(2026, 11, 10));
        when(marketPricesService.latestPrices(eq("11111111000111"), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 30), 1));

        mockMvc.perform(get("/api/v1/markets/11111111000111/prices")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.content[0].productName").value("Arroz Branco 5kg"))
                .andExpect(jsonPath("$.content[0].category").value("GROCERIES"))
                .andExpect(jsonPath("$.content[0].latestPrice").value(24.90))
                .andExpect(jsonPath("$.content[0].observedOn").value("2026-11-10"))
                .andExpect(jsonPath("$.content[0].length()").value(5));

        verify(marketPricesService).latestPrices("11111111000111",
                new MarketPricesService.Filter(null, null, null, MarketPricesSort.RECENT, 0, 30));
    }

    @Test
    void prices_forwardsFiltersSortAndPaging() throws Exception {
        when(marketPricesService.latestPrices(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/markets/11111111000111/prices")
                        .param("query", "arroz").param("category", "GROCERIES").param("days", "90")
                        .param("sort", "CHEAPEST").param("page", "2").param("size", "50")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk());

        verify(marketPricesService).latestPrices("11111111000111",
                new MarketPricesService.Filter("arroz", ProductCategory.GROCERIES, 90, MarketPricesSort.CHEAPEST, 2, 50));
    }

    @Test
    void prices_unknownSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/markets/11111111000111/prices").param("sort", "PRICIEST")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void prices_unknownMarketIs404() throws Exception {
        when(marketPricesService.latestPrices(any(), any()))
                .thenThrow(new MarketNotFoundException("11111111000111"));

        mockMvc.perform(get("/api/v1/markets/11111111000111/prices")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNotFound());
    }

    @Test
    void allEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/markets/11111111000111/prices")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/markets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/markets/watched")).andExpect(status().isUnauthorized());
    }
}
