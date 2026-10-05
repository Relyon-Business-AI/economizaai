package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.AiSweepRunRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.ai.AiFindingService;
import com.relyon.economizaai.service.ai.AiItemFallbackService;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.ai.AiSweepService;
import com.relyon.economizaai.service.ai.AiUsageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
@Import(SecurityConfig.class)
class AiControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AiSweepService aiSweepService;
    @MockitoBean private AiFindingService aiFindingService;
    @MockitoBean private AiItemFallbackService aiItemFallbackService;
    @MockitoBean private AiUsageService aiUsageService;
    @MockitoBean private AiGateway aiGateway;
    @MockitoBean private AiSweepRunRepository aiSweepRunRepository;
    @MockitoBean private ReceiptItemRepository receiptItemRepository;
    @MockitoBean private ProductRepository productRepository;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User adminPrincipal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var admin = User.builder().id(UUID.randomUUID()).email("admin@economizaai.app").household(household).build();
        admin.setRole(Role.ADMIN);
        return admin;
    }

    private AiFinding approved() {
        return AiFinding.builder()
                .id(UUID.randomUUID()).sweepRunId(UUID.randomUUID())
                .type(AiFindingType.FRIENDLY_NAME).status(AiFindingStatus.APPROVED)
                .title("t").payload("{}").build();
    }

    /**
     * Reproduz o bug do Jackson 2 vs 3: um corpo JSON de overrides enviado ao
     * /approve derrubava a request (500 HttpMessageConversionException) porque o
     * conversor do Boot 4 é Jackson 3 e o param era com.fasterxml...JsonNode.
     * Agora o corpo é Map e é convertido internamente — deve responder 200.
     */
    @Test
    void approve_withJsonOverridesBody_returns200() throws Exception {
        var id = UUID.randomUUID();
        when(aiFindingService.approve(eq(id), any())).thenReturn(approved());

        mockMvc.perform(post("/api/v1/categorizer/ai/findings/{id}/approve", id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genericName\":\"Queijo Parmesão\",\"category\":\"FOOD\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiFindingService).approve(eq(id), captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().path("genericName").asText()).isEqualTo("Queijo Parmesão");
    }

    @Test
    void approve_withoutBody_passesNullOverrides() throws Exception {
        var id = UUID.randomUUID();
        when(aiFindingService.approve(eq(id), any())).thenReturn(approved());

        mockMvc.perform(post("/api/v1/categorizer/ai/findings/{id}/approve", id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiFindingService).approve(eq(id), captor.capture());
        assertThat(captor.getValue()).isNull();
    }

    @Test
    void approve_forbiddenForNonAdmin() throws Exception {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").household(household).build();

        mockMvc.perform(post("/api/v1/categorizer/ai/findings/{id}/approve", UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genericName\":\"X\"}"))
                .andExpect(status().isForbidden());
    }
}
