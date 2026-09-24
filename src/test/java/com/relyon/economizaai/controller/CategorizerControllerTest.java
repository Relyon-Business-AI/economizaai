package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategorizerController.class)
@Import(SecurityConfig.class)
class CategorizerControllerTest {

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
    void classify_withoutDescriptionParam_returnsEmptyListNotError() throws Exception {
        when(categorizationDebugService.explainAll(List.of())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categorizer/classify")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void classify_withoutDescriptionParam_passesEmptyListToService() throws Exception {
        when(categorizationDebugService.explainAll(List.of())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categorizer/classify")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(List.class);
        verify(categorizationDebugService).explainAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void status_returnsClassifierState() throws Exception {
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.getLastTrainedAt()).thenReturn(Instant.now());
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);

        mockMvc.perform(get("/api/v1/categorizer/status")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.confidenceThreshold").value(0.75));
    }

    @Test
    void retrain_returnsOutcome() throws Exception {
        when(mlClassifier.retrain()).thenReturn(
                new MlClassifierService.RetrainOutcome(true, 100, 80, Duration.ofMillis(45)));

        mockMvc.perform(post("/api/v1/categorizer/retrain")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trained").value(true))
                .andExpect(jsonPath("$.categoryExamples").value(100));
    }

    @Test
    void retrain_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/categorizer/retrain")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isForbidden());
    }

    @Test
    void autoPromote_returnsOutcome() throws Exception {
        when(autoPromotionService.promote()).thenReturn(
                new AutoPromotionService.PromotionOutcome(3, 1, 2, 18, 5));

        mockMvc.perform(post("/api/v1/categorizer/auto-promote")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promoted").value(3))
                .andExpect(jsonPath("$.learnedTotal").value(5));
    }

    @Test
    void autoPromote_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/categorizer/auto-promote")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isForbidden());
    }
}
