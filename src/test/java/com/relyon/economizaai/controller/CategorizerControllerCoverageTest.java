package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationExplanation;
import com.relyon.economizaai.dto.response.CategorizationExplanation.DictionaryHit;
import com.relyon.economizaai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.extraction.AutoPromotionService;
import com.relyon.economizaai.service.extraction.BrandAliasPromotionService;
import com.relyon.economizaai.service.extraction.CategorizationBenchmarkService;
import com.relyon.economizaai.service.extraction.CategorizationDebugService;
import com.relyon.economizaai.service.extraction.CategorizationQualityService;
import com.relyon.economizaai.service.extraction.CategorizerAdminService;
import com.relyon.economizaai.service.extraction.ConsensusPromotionService;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.PhraseTokenSimulationService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategorizerController.class)
@Import(SecurityConfig.class)
class CategorizerControllerCoverageTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AutoPromotionService autoPromotionService;
    @MockitoBean private CategorizationDebugService categorizationDebugService;
    @MockitoBean private CategorizationBenchmarkService categorizationBenchmarkService;
    @MockitoBean private CategorizationQualityService categorizationQualityService;
    @MockitoBean private ConsensusPromotionService consensusPromotionService;
    @MockitoBean private CategorizerAdminService categorizerAdminService;
    @MockitoBean private EanCatalogService eanCatalogService;
    @MockitoBean private BrandAliasPromotionService brandAliasPromotionService;
    @MockitoBean private PhraseTokenSimulationService phraseTokenSimulationService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    private User adminPrincipal() {
        var admin = principal();
        admin.setRole(Role.ADMIN);
        return admin;
    }

    @Test
    void promoteConsensus_returnsOutcome() throws Exception {
        when(consensusPromotionService.promote())
                .thenReturn(new ConsensusPromotionService.ConsensusOutcome(4, 7, 30));

        mockMvc.perform(post("/api/v1/categorizer/promote-consensus")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productsGraduated").value(4))
                .andExpect(jsonPath("$.tokensLearned").value(7))
                .andExpect(jsonPath("$.learnedTotal").value(30));
    }

    @Test
    void promoteConsensus_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/categorizer/promote-consensus")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isForbidden());
    }

    @Test
    void benchmark_returnsReportAndRecordsSnapshot() throws Exception {
        var report = new CategorizationBenchmarkResponse(
                10, 8, 80.0, 2, 0, 5, 5, 100.0, 4, 4, 100.0,
                List.of(new CategorizationBenchmarkResponse.Failure(
                        "Leite", "category", "MEAT_DAIRY", "OTHER", "DICTIONARY")));
        when(categorizationBenchmarkService.run()).thenReturn(report);

        mockMvc.perform(post("/api/v1/categorizer/benchmark")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.correct").value(8))
                .andExpect(jsonPath("$.accuracyPct").value(80.0))
                .andExpect(jsonPath("$.failures[0].field").value("category"));

        verify(categorizationQualityService).record(CategorizationQualityTrigger.BENCHMARK, report);
    }

    @Test
    void qualityHistory_returnsSnapshots() throws Exception {
        var snapshot = new CategorizationQualitySnapshotResponse(
                LocalDateTime.now(), "BENCHMARK", new BigDecimal("82.50"),
                10, 8, 100, 90, new BigDecimal("90.00"),
                new BigDecimal("100.00"), new BigDecimal("100.00"));
        when(categorizationQualityService.history(5)).thenReturn(List.of(snapshot));

        mockMvc.perform(get("/api/v1/categorizer/quality/history")
                        .param("limit", "5")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trigger").value("BENCHMARK"))
                .andExpect(jsonPath("$[0].benchmarkTotal").value(10));
    }

    @Test
    void qualityHistory_usesDefaultLimit() throws Exception {
        when(categorizationQualityService.history(50)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categorizer/quality/history")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(categorizationQualityService).history(50);
    }

    @Test
    void classify_returnsExplanations() throws Exception {
        var explanation = new CategorizationExplanation(
                "Leite Integral", ProductCategory.MEAT_DAIRY, "Leite", "Italac",
                new BigDecimal("1"), "L", CategorizationSource.DICTIONARY,
                new DictionaryHit("Leite", ProductCategory.MEAT_DAIRY, CategorizationSource.DICTIONARY));
        when(categorizationDebugService.explainAll(anyList())).thenReturn(List.of(explanation));

        mockMvc.perform(get("/api/v1/categorizer/classify")
                        .param("description", "Leite Integral")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].input").value("Leite Integral"))
                .andExpect(jsonPath("$[0].category").value("MEAT_DAIRY"))
                .andExpect(jsonPath("$[0].source").value("DICTIONARY"));
    }

    @Test
    void classify_multipleDescriptions_arePassedThrough() throws Exception {
        var first = new CategorizationExplanation(
                "Milho", ProductCategory.GROCERIES, "Milho", null,
                null, null, CategorizationSource.DICTIONARY, null);
        var second = new CategorizationExplanation(
                "Lays", ProductCategory.GROCERIES, "Batata", "Lays",
                null, null, CategorizationSource.DICTIONARY, null);
        when(categorizationDebugService.explainAll(anyList())).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/v1/categorizer/classify")
                        .param("description", "Milho")
                        .param("description", "Lays")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].input").value("Lays"));
    }

    @Test
    void benchmark_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/categorizer/benchmark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void benchmark_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/categorizer/benchmark")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchBrands_returnsMatches() throws Exception {
        when(categorizerAdminService.searchBrands("dona", 20)).thenReturn(List.of("Dona Benta", "Dona Bela"));

        mockMvc.perform(get("/api/v1/categorizer/brands").param("q", "dona")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Dona Benta"))
                .andExpect(jsonPath("$[1]").value("Dona Bela"));
    }

    @Test
    void searchBrands_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/categorizer/brands").param("q", "dona")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listBrandEntries_returnsRowsWithIds() throws Exception {
        var id = UUID.randomUUID();
        when(categorizerAdminService.listBrandEntries("dona", 50)).thenReturn(List.of(
                new CategorizerAdminService.BrandEntryView(id, "dona benta", "Dona Benta", "CURATED")));

        mockMvc.perform(get("/api/v1/categorizer/brands/entries").param("q", "dona")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Dona Benta"))
                .andExpect(jsonPath("$[0].source").value("CURATED"));
    }

    @Test
    void deleteBrand_removesAndReturnsNoContent() throws Exception {
        var id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/categorizer/brands/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isNoContent());

        verify(categorizerAdminService).deleteBrand(id);
    }

    @Test
    void deleteBrand_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(delete("/api/v1/categorizer/brands/" + UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isForbidden());
    }
}
