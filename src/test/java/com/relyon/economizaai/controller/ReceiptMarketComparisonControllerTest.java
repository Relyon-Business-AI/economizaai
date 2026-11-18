package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.priceindex.ReceiptMarketComparisonService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReceiptMarketComparisonController.class)
@Import(SecurityConfig.class)
class ReceiptMarketComparisonControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReceiptMarketComparisonService receiptMarketComparisonService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void marketComparison_returnsMarketsWithTotalsAndItems() throws Exception {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
        var receiptId = UUID.randomUUID();
        var item = new ReceiptMarketComparisonResponse.Item(UUID.randomUUID(), "Leite integral 1 L",
                new BigDecimal("6"), new BigDecimal("31.74"), new BigDecimal("4.79"), new BigDecimal("28.74"),
                LocalDateTime.of(2026, 11, 15, 0, 0));
        var market = new ReceiptMarketComparisonResponse.Market("22222222000122", "ATACADO SUL SA", "Atacado Sul",
                null, 1.2, 1, new BigDecimal("31.74"), new BigDecimal("28.74"), new BigDecimal("-3.00"),
                List.of(item));
        when(receiptMarketComparisonService.compare(any(User.class), eq(receiptId)))
                .thenReturn(new ReceiptMarketComparisonResponse(receiptId, true,
                        OffsetDateTime.parse("2026-11-16T18:30:00-03:00"), 12, true, List.of(market)));

        mockMvc.perform(get("/api/v1/receipts/" + receiptId + "/market-comparison")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.computedAt").exists())
                .andExpect(jsonPath("$.comparableItemCount").value(12))
                .andExpect(jsonPath("$.locationKnown").value(true))
                .andExpect(jsonPath("$.markets[0].friendlyName").value("Atacado Sul"))
                .andExpect(jsonPath("$.markets[0].matchedItemCount").value(1))
                .andExpect(jsonPath("$.markets[0].difference").value(-3.00))
                .andExpect(jsonPath("$.markets[0].items[0].alternativeUnitPrice").value(4.79));
    }

    @Test
    void marketComparison_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/receipts/" + UUID.randomUUID() + "/market-comparison"))
                .andExpect(status().is4xxClientError());
    }
}
