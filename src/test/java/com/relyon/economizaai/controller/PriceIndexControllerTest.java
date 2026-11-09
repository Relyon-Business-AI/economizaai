package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.GenericComparisonResponse;
import com.relyon.economizaai.dto.response.GenericComparisonResponse.GenericOffer;
import com.relyon.economizaai.dto.response.PriceHistoryResponse;
import com.relyon.economizaai.dto.response.PriceHistoryResponse.PricePoint;
import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.service.geo.NearbyScopeService;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.merchant.SponsoredPromoService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import com.relyon.economizaai.service.priceindex.GenericComparisonService;
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
    @MockitoBean private SponsoredPromoService sponsoredPromoService;
    @MockitoBean private ViewerLocationService viewerLocationService;
    @MockitoBean private NearbyScopeService nearbyScopeService;
    @MockitoBean private GenericComparisonService genericComparisonService;
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
    void inStoreReference_scopesByPrincipalHousehold() throws Exception {
        var pid = UUID.randomUUID();
        var user = principal();
        var ref = new PriceIndexService.ReferencePrice(
                new BigDecimal("7.50"), new BigDecimal("6.90"), new BigDecimal("8.20"),
                12, 4, LocalDateTime.now(), false);
        when(priceIndexService.inStoreReferencePrice(eq(pid), eq(user.getHousehold().getId())))
                .thenReturn(new PriceIndexService.InStoreReferencePrice(
                        PriceIndexService.ReferenceScope.STATE, List.of("RS"), ref));

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/in-store-reference")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("STATE"))
                .andExpect(jsonPath("$.states[0]").value("RS"))
                .andExpect(jsonPath("$.reference.medianPrice").value(7.50))
                .andExpect(jsonPath("$.reference.distinctHouseholds").value(4));
    }

    @Test
    void bestMarkets_withoutPositionUsesViewerLocationCascade() throws Exception {
        var pid = UUID.randomUUID();
        var location = new ViewerLocation(new BigDecimal("-30.03"), new BigDecimal("-51.22"),
                ViewerLocationService.Source.INFERRED, "RS");
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(viewerLocationService.resolve(any())).thenReturn(location);
        when(priceIndexService.bestMarketsForViewer(any(), anyInt(), eq(location), any()))
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
    void genericComparison_returnsFlatOffersCheapestFirst() throws Exception {
        var scannedId = UUID.randomUUID();
        var cheaperId = UUID.randomUUID();
        var response = new GenericComparisonResponse(
                "Creme de Leite", scannedId, "STATE",
                List.of(
                        new GenericOffer(
                                cheaperId, "Italac 200g", "Italac", "200g",
                                new BigDecimal("2.99"), "11111111000100", "SUPERMERCADO A COMERCIO LTDA",
                                "Supermercado A", "Porto Alegre", "RS", null,
                                LocalDateTime.of(2026, 1, 1, 0, 0), "IN_STORE", false),
                        new GenericOffer(
                                scannedId, "Nestlé 200g", "Nestlé", "200g",
                                new BigDecimal("3.49"), "22222222000100", "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA",
                                "Zaffari", "Porto Alegre", "RS", 2.5,
                                LocalDateTime.of(2026, 1, 1, 0, 0), "IN_STORE", true)));
        when(genericComparisonService.forProduct(eq(scannedId), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/price-index/products/" + scannedId + "/generic-comparison")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeName").value("Creme de Leite"))
                .andExpect(jsonPath("$.scope").value("STATE"))
                .andExpect(jsonPath("$.offers[0].productName").value("Italac 200g"))
                .andExpect(jsonPath("$.offers[0].unitPrice").value(2.99))
                .andExpect(jsonPath("$.offers[0].scanned").value(false))
                .andExpect(jsonPath("$.offers[1].scanned").value(true))
                .andExpect(jsonPath("$.offers[1].unitPrice").value(3.49))
                // row shows the friendly name; details keep the full razão social + CNPJ
                .andExpect(jsonPath("$.offers[1].marketFriendlyName").value("Zaffari"))
                .andExpect(jsonPath("$.offers[1].marketName").value("COMPANHIA ZAFFARI COMERCIO E INDUSTRIA"))
                .andExpect(jsonPath("$.offers[1].marketCnpj").value("22222222000100"));
    }

    @Test
    void genericComparisonByType_resolvesFromTypeQuery() throws Exception {
        var response = new GenericComparisonResponse("Creme de Leite", null, "NATIONAL",
                List.of(new GenericOffer(UUID.randomUUID(), "Italac 200g", "Italac", "200g",
                        new BigDecimal("2.99"), "11111111000100", "SUPERMERCADO A COMERCIO LTDA",
                        "Supermercado A", "Porto Alegre", "RS", null,
                        LocalDateTime.of(2026, 1, 1, 0, 0), "IN_STORE", false)));
        when(genericComparisonService.forType(eq("Creme de Leite"), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/price-index/generic-comparison")
                        .param("type", "Creme de Leite")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeName").value("Creme de Leite"))
                .andExpect(jsonPath("$.scannedProductId").doesNotExist())
                .andExpect(jsonPath("$.offers[0].productName").value("Italac 200g"));
    }

    @Test
    void priceHistoryForMarket_returnsPricePointsForPair() throws Exception {
        var pid = UUID.randomUUID();
        var cnpj = "22222222000100";
        var response = new PriceHistoryResponse(pid, "Creme de Leite Nestlé", null,
                List.of(new PricePoint(LocalDateTime.of(2026, 1, 1, 0, 0), cnpj,
                        "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA", "Zaffari",
                        new BigDecimal("3.49"), new BigDecimal("1.000"))));
        when(genericComparisonService.priceHistoryForMarket(eq(pid), eq(cnpj), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/markets/" + cnpj + "/price-history")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Creme de Leite Nestlé"))
                .andExpect(jsonPath("$.points[0].unitPrice").value(3.49))
                .andExpect(jsonPath("$.points[0].marketFriendlyName").value("Zaffari"));
    }

    @Test
    void priceHistoryForMarket_returnsEmptyPointsWhenBlocked() throws Exception {
        var pid = UUID.randomUUID();
        var cnpj = "44444444000100";
        when(genericComparisonService.priceHistoryForMarket(eq(pid), eq(cnpj), any()))
                .thenReturn(new PriceHistoryResponse(pid, "Remédio X", null, List.of()));

        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/markets/" + cnpj + "/price-history")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isEmpty());
    }

    @Test
    void promos_returnsCurrentPromos() throws Exception {
        var scope = new NearbyScope(new ViewerLocation(null, null, ViewerLocationService.Source.NONE, "RS"), Set.of(), 10);
        when(nearbyScopeService.forUser(any(), isNull())).thenReturn(scope);
        when(communityPromoService.detectInScope(scope))
                .thenReturn(List.of(
                        new CommunityPromoService.CommunityPromo(UUID.randomUUID(), "Arroz",
                                "93015006005182", "93015006", "Mercado X", "Mercado X",
                                new BigDecimal("22"), new BigDecimal("28"), new BigDecimal("21.43"),
                                5, 3L, null, false, "RS")
                ));

        mockMvc.perform(get("/api/v1/price-index/promos")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Arroz"))
                .andExpect(jsonPath("$[0].dropPct").value(21.43));
    }

    @Test
    void promosFeed_wrapsPromosWithLocationContext() throws Exception {
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(viewerLocationService.resolve(any()))
                .thenReturn(new ViewerLocation(new BigDecimal("-30.05"), new BigDecimal("-51.20"),
                        ViewerLocationService.Source.INFERRED, "RS"));
        when(communityPromoService.detectAll(any(), any(), isNull(), any()))
                .thenReturn(List.of(
                        new CommunityPromoService.CommunityPromo(UUID.randomUUID(), "Arroz",
                                "93015006005182", "93015006", "Mercado X", "Mercado X",
                                new BigDecimal("22"), new BigDecimal("28"), new BigDecimal("21.43"),
                                5, 3L, 2.0, false, "RS")
                ));

        mockMvc.perform(get("/api/v1/price-index/promos/feed")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationSource").value("INFERRED"))
                .andExpect(jsonPath("$.uf").value("RS"))
                .andExpect(jsonPath("$.promos[0].productName").value("Arroz"))
                .andExpect(jsonPath("$.promos[0].uf").value("RS"));
    }

    @Test
    void allEndpointsRequireAuth() throws Exception {
        var pid = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/price-index/promos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/best-markets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/in-store-reference")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/price-index/products/" + pid + "/markets/22222222000100/price-history"))
                .andExpect(status().isUnauthorized());
    }
}
