package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationExplanation;
import com.relyon.economizaai.dto.response.CategorizationExplanation.DictionaryHit;
import com.relyon.economizaai.dto.response.CategorizationExplanation.MlGuess;
import com.relyon.economizaai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizaai.dto.response.MlClassificationResponse;
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
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategorizerController.class)
@Import(SecurityConfig.class)
class CategorizerControllerCoverageTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MlClassifierService mlClassifier;
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
                10, 8, 80.0, 2, 0, 5, 5, 100.0, 4, 4, 100.0, 10, 7, 70.0,
                List.of(new CategorizationBenchmarkResponse.Failure(
                        "Leite", "category", "MEAT_DAIRY", "OTHER", "ML")));
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
                new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("70.00"), true);
        when(categorizationQualityService.history(5)).thenReturn(List.of(snapshot));

        mockMvc.perform(get("/api/v1/categorizer/quality/history")
                        .param("limit", "5")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trigger").value("BENCHMARK"))
                .andExpect(jsonPath("$[0].benchmarkTotal").value(10))
                .andExpect(jsonPath("$[0].mlReady").value(true));
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
                new DictionaryHit("Leite", ProductCategory.MEAT_DAIRY, CategorizationSource.DICTIONARY),
                new MlGuess("MEAT_DAIRY", 0.92, true),
                new MlGuess("Leite", 0.88, true),
                true, true, 0.75);
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
                null, null, CategorizationSource.DICTIONARY,
                null, null, null, false, false, 0.75);
        var second = new CategorizationExplanation(
                "Lays", ProductCategory.GROCERIES, "Batata", "Lays",
                null, null, CategorizationSource.ML,
                null, null, null, true, true, 0.75);
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
    void mlPredict_returnsPredictions() throws Exception {
        var prediction = new MlClassificationResponse(
                "Lays", new MlClassificationResponse.Guess("GROCERIES", 0.81, true),
                new MlClassificationResponse.Guess("Batata Frita", 0.65, false),
                true, 0.75);
        when(categorizationDebugService.mlPredictAll(anyList())).thenReturn(List.of(prediction));

        mockMvc.perform(get("/api/v1/categorizer/ml/predict")
                        .param("description", "Lays")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].input").value("Lays"))
                .andExpect(jsonPath("$[0].category.label").value("GROCERIES"))
                .andExpect(jsonPath("$[0].ready").value(true));
    }

    @Test
    void status_notReady_returnsState() throws Exception {
        when(mlClassifier.isReady()).thenReturn(false);
        when(mlClassifier.getLastTrainedAt()).thenReturn(null);
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.5);

        mockMvc.perform(get("/api/v1/categorizer/status")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.confidenceThreshold").value(0.5));
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
}
