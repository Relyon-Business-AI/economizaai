package com.relyon.economizai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.dto.request.MergeProductRequest;
import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.dto.request.SetProductBrandRequest;
import com.relyon.economizai.dto.response.AdminUserDetailResponse;
import com.relyon.economizai.dto.response.AdminUserDetailResponse.ReceiptCounts;
import com.relyon.economizai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizai.dto.response.BrandBackfillResponse;
import com.relyon.economizai.dto.response.DuplicateProductGroupResponse;
import com.relyon.economizai.dto.response.MissingBrandProductResponse;
import com.relyon.economizai.dto.response.ProductDeletionResponse;
import com.relyon.economizai.dto.response.ProductMergeResultResponse;
import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.dto.response.ReceiptItemResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.dto.response.RecategorizeReportResponse;
import com.relyon.economizai.dto.response.RelevanceReportResponse;
import com.relyon.economizai.dto.response.RecategorizeResultResponse;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.ReceiptService;
import com.relyon.economizai.service.admin.AdminLlmService;
import com.relyon.economizai.service.admin.AdminMerchantService;
import com.relyon.economizai.service.admin.AdminNotificationService;
import com.relyon.economizai.service.admin.AdminProductService;
import com.relyon.economizai.service.admin.AdminDevService;
import com.relyon.economizai.service.admin.AdminReceiptService;
import com.relyon.economizai.service.admin.AdminUserService;
import com.relyon.economizai.service.extraction.CategorizationQualityService;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.notifications.RelevanceReportService;
import com.relyon.economizai.service.analytics.AdminAnalyticsService;
import com.relyon.economizai.service.analytics.meta.MetaAdSpendSyncJob;
import com.relyon.economizai.service.paidapi.CostReportService;
import com.relyon.economizai.service.sefaz.SefazIngestionService;
import com.relyon.economizai.service.sefaz.StateCoverageService;
import com.relyon.economizai.dto.response.CostReportResponse;
import com.relyon.economizai.dto.response.StateCoverageResponse;
import com.relyon.economizai.service.geo.MarketLocationService.SegmentClassificationSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private ReceiptService receiptService;
    @MockitoBean private AdminUserService adminUserService;
    @MockitoBean private AdminReceiptService adminReceiptService;
    @MockitoBean private AdminNotificationService adminNotificationService;
    @MockitoBean private AdminMerchantService adminMerchantService;
    @MockitoBean private AdminLlmService adminLlmService;
    @MockitoBean private AdminProductService adminProductService;
    @MockitoBean private CategorizationQualityService categorizationQualityService;
    @MockitoBean private MarketLocationService marketLocationService;
    @MockitoBean private RelevanceReportService relevanceReportService;
    @MockitoBean private CostReportService costReportService;
    @MockitoBean private AdminAnalyticsService adminAnalyticsService;
    @MockitoBean private MetaAdSpendSyncJob metaAdSpendSyncJob;
    @MockitoBean private StateCoverageService stateCoverageService;
    @MockitoBean private SefazIngestionService sefazIngestionService;
    @MockitoBean private AdminDevService adminDevService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User regularUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("user@test.com").household(household).build();
    }

    private User adminUser() {
        var admin = regularUser();
        admin.setRole(Role.ADMIN);
        return admin;
    }

    private ReceiptResponse sampleReceipt(ReceiptStatus status) {
        return new ReceiptResponse(
                UUID.randomUUID(), CHAVE_RS, "https://sefaz/p=" + CHAVE_RS, UnidadeFederativa.RS, "12345678000190",
                "Mercado X", "Mercado X", "Rua Y, 123", LocalDateTime.now(),
                new BigDecimal("57.80"), new BigDecimal("57.80"), null, null, null, null,
                status, null, null, null, LocalDateTime.now(),
                List.of(new ReceiptItemResponse(
                        UUID.randomUUID(), UUID.randomUUID(), 1, "ARROZ 5KG", null, "ARROZ 5KG",
                        "7891234567890", new BigDecimal("1"), "UN",
                        new BigDecimal("28.90"), new BigDecimal("28.90"), null, null, false, false, false, false, null, false)));
    }

    private ProductResponse sampleProduct() {
        return new ProductResponse(UUID.randomUUID(), "7891234567890", "ARROZ TIO J 5KG",
                "Arroz", null, "Tio João", ProductCategory.GROCERIES, "UN",
                new BigDecimal("5"), "KG", CategorizationSource.DICTIONARY, false);
    }

    // --- reparse receipt ---

    @Test
    void reparseReceipt_returnsReparsedReceipt() throws Exception {
        var id = UUID.randomUUID();
        when(receiptService.reparse(id)).thenReturn(sampleReceipt(ReceiptStatus.CONFIRMED));

        mockMvc.perform(post("/api/v1/admin/receipts/" + id + "/reparse")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void reparseReceipt_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/receipts/" + UUID.randomUUID() + "/reparse")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void seedDiscountedReceipt_forbiddenWhenDevSeedDisabled() throws Exception {
        // economizai.admin.dev-seed-enabled defaults to false in the test context.
        mockMvc.perform(post("/api/v1/admin/dev/seed-discounted-receipt")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void reparseReceipt_unauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/admin/receipts/" + UUID.randomUUID() + "/reparse"))
                .andExpect(status().isUnauthorized());
    }

    // --- observations: orphaned count + dev-gated cleanup ---

    @Test
    void orphanedObservationCount_returnsCount() throws Exception {
        when(adminReceiptService.countOrphanedObservations()).thenReturn(607L);

        mockMvc.perform(get("/api/v1/admin/observations/orphaned-count")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orphaned").value(607));
    }

    @Test
    void purgeOrphanedObservations_forbiddenWhenDevCleanupDisabled() throws Exception {
        // devCleanupEnabled defaults to false in the slice — even an admin is blocked.
        mockMvc.perform(delete("/api/v1/admin/observations/orphaned")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isForbidden());
        verify(adminReceiptService, never()).deleteOrphanedObservations();
    }

    @Test
    void purgeOrphanedObservations_unauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/observations/orphaned"))
                .andExpect(status().isUnauthorized());
    }

    // --- list / get users ---

    @Test
    void listUsers_returnsPagedSummaries() throws Exception {
        var summary = new AdminUserSummaryResponse(UUID.randomUUID(), "John", "john@test.com",
                Role.USER, SubscriptionTier.FREE, true, true, false, UUID.randomUUID(), LocalDateTime.now());
        Page<AdminUserSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(adminUserService.list(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/users")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("john@test.com"));
    }

    @Test
    void listUsers_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void costReport_returnsSpendBreakdown() throws Exception {
        var report = new CostReportResponse(30, 40L, 330L, new BigDecimal("3.30"),
                5000L, new BigDecimal("1.20"),
                List.of(new CostReportResponse.ServiceSpendLine("INFOSIMPLES", 10L, 2L, 240L, new BigDecimal("2.40"))),
                List.of(new CostReportResponse.StateSpendLine("CE", 10L, 240L, new BigDecimal("2.40"))));
        when(costReportService.report(30)).thenReturn(report);

        mockMvc.perform(get("/api/v1/admin/costs")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCostReais").value(3.30))
                .andExpect(jsonPath("$.byState[0].uf").value("CE"));
    }

    @Test
    void costReport_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/costs")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void stateCoverage_returnsPerUfMap() throws Exception {
        var report = new StateCoverageResponse(List.of(new StateCoverageResponse.StateCoverageEntry(
                UnidadeFederativa.BA, "EXPERIMENTAL", 7, 5, 2, LocalDateTime.now().atOffset(ZoneOffset.UTC),
                Map.of("QR_PORTAL", new StateCoverageResponse.StateCoverageEntry.StrategyStats(5, 2)))));
        when(sefazIngestionService.getVerifiedStates()).thenReturn(Set.of(UnidadeFederativa.RS));
        when(sefazIngestionService.experimentalStates()).thenReturn(Set.of(UnidadeFederativa.BA));
        when(stateCoverageService.report(Set.of(UnidadeFederativa.RS), Set.of(UnidadeFederativa.BA)))
                .thenReturn(report);

        mockMvc.perform(get("/api/v1/admin/state-coverage")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.states[0].uf").value("BA"))
                .andExpect(jsonPath("$.states[0].mode").value("EXPERIMENTAL"))
                .andExpect(jsonPath("$.states[0].strategies.QR_PORTAL.successes").value(5));
    }

    @Test
    void stateCoverage_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/state-coverage")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUser_returnsDetail() throws Exception {
        var id = UUID.randomUUID();
        var detail = new AdminUserDetailResponse(id, "John", "john@test.com",
                Role.USER, SubscriptionTier.FREE, true, true, true, false, UUID.randomUUID(),
                3L, new ReceiptCounts(1L, 5L, 0L, 2L), new BigDecimal("99.90"), LocalDateTime.now());
        when(adminUserService.get(id)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/admin/users/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("john@test.com"))
                .andExpect(jsonPath("$.receipts.confirmed").value(5))
                .andExpect(jsonPath("$.householdMemberCount").value(3));
    }

    @Test
    void getUser_returns404WhenMissing() throws Exception {
        var id = UUID.randomUUID();
        when(adminUserService.get(id)).thenThrow(new UserNotFoundException(id.toString()));

        mockMvc.perform(get("/api/v1/admin/users/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isNotFound());
    }

    // --- delete user ---

    @Test
    void deleteUser_returnsNoContent() throws Exception {
        var id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/admin/users/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isNoContent());

        verify(adminUserService).delete(id);
    }

    // --- set subscription tier ---

    @Test
    void setSubscriptionTier_returnsUpdatedDetail() throws Exception {
        var id = UUID.randomUUID();
        var detail = new AdminUserDetailResponse(id, "John", "john@test.com",
                Role.USER, SubscriptionTier.PRO, true, true, true, false, UUID.randomUUID(),
                1L, new ReceiptCounts(0L, 0L, 0L, 0L), BigDecimal.ZERO, LocalDateTime.now());
        when(adminUserService.setTier(id, SubscriptionTier.PRO)).thenReturn(detail);

        mockMvc.perform(put("/api/v1/admin/users/" + id + "/subscription-tier")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionTier").value("PRO"));

        verify(adminUserService).setTier(id, SubscriptionTier.PRO);
    }

    @Test
    void setSubscriptionTier_returns400WhenTierMissing() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/" + UUID.randomUUID() + "/subscription-tier")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setSubscriptionTier_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/" + UUID.randomUUID() + "/subscription-tier")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"PRO\"}"))
                .andExpect(status().isForbidden());
    }

    // --- list / get receipts cross-household ---

    @Test
    void listReceipts_returnsPagedSummaries() throws Exception {
        var summary = new ReceiptSummaryResponse(UUID.randomUUID(), "Mercado X", "Mercado X", LocalDateTime.now(),
                new BigDecimal("57.80"), new BigDecimal("57.80"), null, null, 1, ReceiptStatus.CONFIRMED);
        Page<ReceiptSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(adminReceiptService.list(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/receipts")
                        .param("q", "arroz")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].marketName").value("Mercado X"));
    }

    @Test
    void listReceipts_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReceipt_returnsReceipt() throws Exception {
        var id = UUID.randomUUID();
        when(adminReceiptService.get(id)).thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(get("/api/v1/admin/receipts/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaveAcesso").value(CHAVE_RS));
    }

    @Test
    void getReceipt_returns404WhenMissing() throws Exception {
        var id = UUID.randomUUID();
        when(adminReceiptService.get(id)).thenThrow(new ReceiptNotFoundException());

        mockMvc.perform(get("/api/v1/admin/receipts/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isNotFound());
    }

    // --- send test notification ---

    @Test
    void sendTestNotification_returns202() throws Exception {
        var request = new SendTestNotificationRequest("user@test.com", "Hi", "Body", null);

        mockMvc.perform(post("/api/v1/admin/notifications/test")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(adminNotificationService).sendTest(any(SendTestNotificationRequest.class));
    }

    @Test
    void sendTestNotification_returns400ForInvalidEmail() throws Exception {
        var request = new SendTestNotificationRequest("not-an-email", null, null, null);

        mockMvc.perform(post("/api/v1/admin/notifications/test")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(adminNotificationService, never()).sendTest(any());
    }

    // --- relevance report ---

    @Test
    void relevanceReport_returnsReportForAdmin() throws Exception {
        when(relevanceReportService.report(30)).thenReturn(new RelevanceReportResponse(
                "SHADOW", 30, 14, 180,
                new RelevanceReportResponse.Engagement(5, 1, 2, 10, 4, 1, 2, 0, 1,
                        new BigDecimal("12.50"), new BigDecimal("0.4000"), new BigDecimal("0.2000")),
                new RelevanceReportResponse.Suppression(3, 2, 2, 0, BigDecimal.ZERO)));

        mockMvc.perform(get("/api/v1/admin/notifications/relevance-report")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SHADOW"))
                .andExpect(jsonPath("$.engagement.dealViews").value(10))
                .andExpect(jsonPath("$.suppression.regretEngagements").value(0));
    }

    @Test
    void relevanceReport_forbiddenForRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notifications/relevance-report")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void relevanceReport_clampsNonPositiveDaysToOne() throws Exception {
        when(relevanceReportService.report(1)).thenReturn(new RelevanceReportResponse(
                "SHADOW", 1, 14, 180,
                new RelevanceReportResponse.Engagement(0, 0, 0, 0, 0, 0, 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new RelevanceReportResponse.Suppression(0, 0, 0, 0, BigDecimal.ZERO)));

        mockMvc.perform(get("/api/v1/admin/notifications/relevance-report?days=-5")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(1));
    }

    @Test
    void sendTestNotification_returns404WhenTargetUnknown() throws Exception {
        var request = new SendTestNotificationRequest("ghost@test.com", null, null, null);
        doThrow(new UserNotFoundException("ghost@test.com"))
                .when(adminNotificationService).sendTest(any(SendTestNotificationRequest.class));

        mockMvc.perform(post("/api/v1/admin/notifications/test")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendTestNotification_forbiddenForNonAdmin() throws Exception {
        var request = new SendTestNotificationRequest("user@test.com", null, null, null);

        mockMvc.perform(post("/api/v1/admin/notifications/test")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // --- products list ---

    @Test
    void listProducts_returnsPagedCatalog() throws Exception {
        Page<ProductResponse> page = new PageImpl<>(List.of(sampleProduct()));
        when(adminProductService.listAll(any(), any(), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/products")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].brand").value("Tio João"));
    }

    @Test
    void listProducts_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/products")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    // --- markets/classify-segments ---

    @Test
    void classifyMarketSegments_returnsSummary() throws Exception {
        when(marketLocationService.classifyPendingSegments())
                .thenReturn(new SegmentClassificationSummary(10, 3, 4, 1, 0, 1, 1));

        mockMvc.perform(post("/api/v1/admin/markets/classify-segments")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(10))
                .andExpect(jsonPath("$.pharmacy").value(3))
                .andExpect(jsonPath("$.stillUnknown").value(1));
    }

    @Test
    void classifyMarketSegments_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/markets/classify-segments")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    // --- products/refresh-brands ---

    @Test
    void refreshBrands_returnsBackfillOutcome() throws Exception {
        when(adminProductService.backfillBrands()).thenReturn(new BrandBackfillResponse(100L, 12, 8));

        mockMvc.perform(post("/api/v1/admin/products/refresh-brands")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filled").value(12))
                .andExpect(jsonPath("$.stillMissing").value(8));
    }

    @Test
    void refreshBrands_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products/refresh-brands")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    // --- products/missing-brand ---

    @Test
    void listMissingBrand_returnsPagedProducts() throws Exception {
        var response = new MissingBrandProductResponse(UUID.randomUUID(), "789", "ARROZ",
                "Arroz", ProductCategory.GROCERIES, new BigDecimal("5"), "KG", List.of("ARROZ 5KG"));
        Page<MissingBrandProductResponse> page = new PageImpl<>(List.of(response));
        when(adminProductService.listMissingBrand(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/products/missing-brand")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].normalizedName").value("ARROZ"))
                .andExpect(jsonPath("$.content[0].sampleDescriptions[0]").value("ARROZ 5KG"));
    }

    // --- products/{id}/brand ---

    @Test
    void setProductBrand_returnsUpdatedProduct() throws Exception {
        var id = UUID.randomUUID();
        when(adminProductService.setBrand(eq(id), any(SetProductBrandRequest.class)))
                .thenReturn(sampleProduct());

        mockMvc.perform(patch("/api/v1/admin/products/" + id + "/brand")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetProductBrandRequest("Tio João"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand").value("Tio João"));
    }

    @Test
    void setProductBrand_returns400ForBlankBrand() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/products/" + UUID.randomUUID() + "/brand")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetProductBrandRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setProductBrand_returns404WhenProductMissing() throws Exception {
        var id = UUID.randomUUID();
        when(adminProductService.setBrand(eq(id), any(SetProductBrandRequest.class)))
                .thenThrow(new ProductNotFoundException());

        mockMvc.perform(patch("/api/v1/admin/products/" + id + "/brand")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetProductBrandRequest("Tio João"))))
                .andExpect(status().isNotFound());
    }

    // --- DELETE product ---

    @Test
    void deleteProduct_returnsDeletionResponse() throws Exception {
        var id = UUID.randomUUID();
        when(adminProductService.delete(id, false))
                .thenReturn(new ProductDeletionResponse(id, 0L));

        mockMvc.perform(delete("/api/v1/admin/products/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(id.toString()))
                .andExpect(jsonPath("$.receiptItemsDetached").value(0));
    }

    @Test
    void deleteProduct_withForceTrue_passesForceFlag() throws Exception {
        var id = UUID.randomUUID();
        when(adminProductService.delete(id, true))
                .thenReturn(new ProductDeletionResponse(id, 4L));

        mockMvc.perform(delete("/api/v1/admin/products/" + id)
                        .param("force", "true")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptItemsDetached").value(4));
    }

    @Test
    void deleteProduct_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/products/" + UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
    }

    // --- products/duplicates ---

    @Test
    void listDuplicates_returnsGroups() throws Exception {
        var group = new DuplicateProductGroupResponse("Arroz", "Tio Joao",
                new BigDecimal("5"), "KG", ProductCategory.GROCERIES, List.of(sampleProduct()));
        when(adminProductService.listDuplicateGroups()).thenReturn(List.of(group));

        mockMvc.perform(get("/api/v1/admin/products/duplicates")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].genericName").value("Arroz"))
                .andExpect(jsonPath("$[0].products[0].brand").value("Tio João"));
    }

    // --- products/{id}/merge ---

    @Test
    void mergeProduct_returnsMergeResult() throws Exception {
        var survivorId = UUID.randomUUID();
        var absorbedId = UUID.randomUUID();
        when(adminProductService.merge(eq(survivorId), any(MergeProductRequest.class)))
                .thenReturn(new ProductMergeResultResponse(survivorId, absorbedId, false, true,
                        2L, 5L, 7L, 0L, 1L, 0L, 1L, 0L, 0L));

        mockMvc.perform(post("/api/v1/admin/products/" + survivorId + "/merge")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MergeProductRequest(absorbedId, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.aliasesMigrated").value(2));
    }

    @Test
    void mergeProduct_returns400WhenAbsorbedIdMissing() throws Exception {
        var survivorId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/products/" + survivorId + "/merge")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":false}"))
                .andExpect(status().isBadRequest());
    }

    // --- products/recategorize report (GET) ---

    @Test
    void recategorizeReport_returnsDryRunReport() throws Exception {
        when(adminProductService.recategorizeReport())
                .thenReturn(new RecategorizeReportResponse(100L, 3, 1, 1, 1, List.of()));

        mockMvc.perform(get("/api/v1/admin/products/recategorize")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(3))
                .andExpect(jsonPath("$.applicableFromDictionary").value(1));
    }

    // --- products/recategorize apply (POST) ---

    @Test
    void recategorizeApply_default_appliesAndRecordsQualitySnapshot() throws Exception {
        when(adminProductService.recategorizeApply(false))
                .thenReturn(new RecategorizeResultResponse(100L, 5, 1, 2, 92));

        mockMvc.perform(post("/api/v1/admin/products/recategorize")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(5))
                .andExpect(jsonPath("$.skippedMl").value(2));

        verify(adminProductService).recategorizeApply(false);
        verify(categorizationQualityService).measureAndRecord(CategorizationQualityTrigger.BACKFILL);
    }

    @Test
    void recategorizeApply_includeMl_passesFlag() throws Exception {
        when(adminProductService.recategorizeApply(true))
                .thenReturn(new RecategorizeResultResponse(100L, 8, 1, 0, 91));

        mockMvc.perform(post("/api/v1/admin/products/recategorize")
                        .param("includeMl", "true")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(8));

        verify(adminProductService).recategorizeApply(true);
    }

    @Test
    void recategorizeApply_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products/recategorize")
                        .with(SecurityMockMvcRequestPostProcessors.user(regularUser())))
                .andExpect(status().isForbidden());
        verify(adminProductService, never()).recategorizeApply(anyBoolean());
    }
}
