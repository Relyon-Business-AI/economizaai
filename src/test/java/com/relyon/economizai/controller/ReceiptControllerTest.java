package com.relyon.economizai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.dto.response.ChaveExtractionResponse;
import com.relyon.economizai.dto.response.ConfirmReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptItemResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.exception.InvalidReceiptPhotoException;
import com.relyon.economizai.exception.OcrUnavailableException;
import com.relyon.economizai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizai.exception.ReceiptNotEditableException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.ReceiptExportService;
import com.relyon.economizai.service.ReceiptImportService;
import com.relyon.economizai.service.ReceiptService;
import com.relyon.economizai.service.llm.PhotoReceiptExtractionService;
import com.relyon.economizai.service.report.ReportEmailService;
import com.relyon.economizai.service.scan.ChaveAcessoOcrService;
import com.relyon.economizai.service.scan.QrCodePhotoDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReceiptController.class)
@Import(SecurityConfig.class)
class ReceiptControllerTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ReceiptService receiptService;

    @MockitoBean
    private ReceiptImportService receiptImportService;

    @MockitoBean
    private ReceiptExportService receiptExportService;

    @MockitoBean
    private ReportEmailService reportEmailService;

    @MockitoBean
    private PhotoReceiptExtractionService photoReceiptExtractionService;

    @MockitoBean
    private QrCodePhotoDecoder qrCodePhotoDecoder;

    @MockitoBean
    private ChaveAcessoOcrService chaveAcessoOcrService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private ReceiptResponse sampleReceipt(ReceiptStatus status) {
        return new ReceiptResponse(
                UUID.randomUUID(),
                CHAVE_RS,
                "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|3|1",
                UnidadeFederativa.RS,
                "12345678000190",
                "Mercado X",
                "Mercado X",
                "Rua Y, 123",
                LocalDateTime.now(),
                new BigDecimal("57.80"),
                new BigDecimal("57.80"),
                null,
                null,
                null,
                null,
                status,
                null,
                null,
                status == ReceiptStatus.CONFIRMED ? LocalDateTime.now() : null,
                LocalDateTime.now(),
                List.of(new ReceiptItemResponse(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        "ARROZ TIO J 5KG",
                        null,
                        "ARROZ TIO J 5KG",
                        "7891234567890",
                        new BigDecimal("2"),
                        "UN",
                        new BigDecimal("28.90"),
                        new BigDecimal("57.80"),
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        null,
                        false
                ))
        );
    }

    @Test
    void submit_returns201WithParsedReceipt() throws Exception {
        var user = buildUser();
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class), anyBoolean()))
                .thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.items[0].rawDescription").value("ARROZ TIO J 5KG"))
                .andExpect(jsonPath("$.uf").value("RS"));
    }

    @Test
    void submit_withDeviceFetchHeader_marksClientDeviceCapable() throws Exception {
        var user = buildUser();
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class), anyBoolean()))
                .thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .header("X-Device-Fetch", "df3:skip-uf43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isCreated());

        verify(receiptService).submit(any(User.class), any(SubmitReceiptRequest.class), eq(true));
    }

    @Test
    void submit_withoutDeviceFetchHeader_marksClientNotDeviceCapable() throws Exception {
        var user = buildUser();
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class), anyBoolean()))
                .thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isCreated());

        verify(receiptService).submit(any(User.class), any(SubmitReceiptRequest.class), eq(false));
    }

    @Test
    void submit_returns409WhenChaveAlreadyIngested() throws Exception {
        var user = buildUser();
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class), anyBoolean()))
                .thenThrow(new ReceiptAlreadyIngestedException(CHAVE_RS));

        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isConflict());
    }

    @Test
    void submit_returns400ForBlankPayload() throws Exception {
        var user = buildUser();
        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitPhoto_returns201WhenQrDecodes() throws Exception {
        var user = buildUser();
        var photo = new MockMultipartFile("file", "qr.png", "image/png", new byte[]{1, 2, 3});
        when(qrCodePhotoDecoder.decode(any(MultipartFile.class))).thenReturn(CHAVE_RS + "|2|1");
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class)))
                .thenReturn(sampleReceipt(ReceiptStatus.PROCESSING));

        mockMvc.perform(multipart("/api/v1/receipts/photo")
                        .file(photo)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void submitPhoto_returns400WhenQrUnreadable() throws Exception {
        var user = buildUser();
        var photo = new MockMultipartFile("file", "blurry.png", "image/png", new byte[]{1, 2, 3});
        when(qrCodePhotoDecoder.decode(any(MultipartFile.class)))
                .thenThrow(new InvalidReceiptPhotoException("receipt.photo.qr.unreadable"));

        mockMvc.perform(multipart("/api/v1/receipts/photo")
                        .file(photo)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitPhoto_requiresAuth() throws Exception {
        var photo = new MockMultipartFile("file", "qr.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/receipts/photo").file(photo))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void extractChaveFromPhoto_returnsExtractedChave() throws Exception {
        var user = buildUser();
        var photo = new MockMultipartFile("file", "chave.png", "image/png", new byte[]{1, 2, 3});
        when(chaveAcessoOcrService.extractChave(any(MultipartFile.class)))
                .thenReturn(new ChaveExtractionResponse(CHAVE_RS, UnidadeFederativa.RS));

        mockMvc.perform(multipart("/api/v1/receipts/chave/photo")
                        .file(photo)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaveAcesso").value(CHAVE_RS))
                .andExpect(jsonPath("$.uf").value("RS"));
    }

    @Test
    void extractChaveFromPhoto_returns503WhenOcrUnavailable() throws Exception {
        var user = buildUser();
        var photo = new MockMultipartFile("file", "chave.png", "image/png", new byte[]{1, 2, 3});
        when(chaveAcessoOcrService.extractChave(any(MultipartFile.class)))
                .thenThrow(new OcrUnavailableException());

        mockMvc.perform(multipart("/api/v1/receipts/chave/photo")
                        .file(photo)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void list_returnsPagedSummaries() throws Exception {
        var user = buildUser();
        var summary = new ReceiptSummaryResponse(UUID.randomUUID(), "Mercado X", "Mercado X", LocalDateTime.now(),
                new BigDecimal("57.80"), new BigDecimal("57.80"), null, null, 1, ReceiptStatus.CONFIRMED);
        Page<ReceiptSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(receiptService.list(any(User.class), isNull(), isNull(), isNull(), isNull(List.class), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].marketName").value("Mercado X"))
                .andExpect(jsonPath("$.content[0].itemCount").value(1));
    }

    @Test
    void list_validStatus_isAccepted() throws Exception {
        var user = buildUser();
        var summary = new ReceiptSummaryResponse(UUID.randomUUID(), "Mercado X", "Mercado X", LocalDateTime.now(),
                new BigDecimal("57.80"), new BigDecimal("57.80"), null, null, 1, ReceiptStatus.CONFIRMED);
        Page<ReceiptSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(receiptService.list(any(User.class), isNull(), isNull(), isNull(), isNull(List.class),
                eq(ReceiptStatus.CONFIRMED), isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/receipts")
                        .param("status", "CONFIRMED")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
    }

    @Test
    void export_lowercaseCsvAndXlsxFormats_bindAndDownload() throws Exception {
        var user = buildUser();
        when(receiptExportService.exportPurchaseHistory(any(), any(), any(), eq(ReceiptExportService.ExportFormat.CSV)))
                .thenReturn(new ReceiptExportService.ExportFile("data".getBytes(), "text/csv", "csv"));
        when(receiptExportService.exportPurchaseHistory(any(), any(), any(), eq(ReceiptExportService.ExportFormat.XLSX)))
                .thenReturn(new ReceiptExportService.ExportFile(new byte[]{80, 75}, 
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"));

        mockMvc.perform(get("/api/v1/receipts/export")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")));

        mockMvc.perform(get("/api/v1/receipts/export").param("format", "xlsx")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
    }

    @Test
    void export_emailDelivery_returns202AndSendsToOwnEmail() throws Exception {
        var user = buildUser();
        when(receiptExportService.exportPurchaseHistory(any(), any(), any(), eq(ReceiptExportService.ExportFormat.PDF)))
                .thenReturn(new ReceiptExportService.ExportFile("%PDF-".getBytes(), "application/pdf", "pdf"));

        mockMvc.perform(get("/api/v1/receipts/export")
                        .param("format", "pdf").param("delivery", "email")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isAccepted());

        verify(reportEmailService).sendToOwnEmail(any(), any(), contains(".pdf"));
    }

    @Test
    void export_unknownDelivery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/receipts/export").param("delivery", "pombo-correio")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_unknownFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/receipts/export").param("format", "docx")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_invalidStatus_returns400() throws Exception {
        var user = buildUser();

        mockMvc.perform(get("/api/v1/receipts")
                        .param("status", "GARBAGE")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returnsReceipt() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.get(any(User.class), eq(id))).thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(get("/api/v1/receipts/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaveAcesso").value(CHAVE_RS));
    }

    @Test
    void get_returns404WhenMissing() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.get(any(User.class), eq(id))).thenThrow(new ReceiptNotFoundException());

        mockMvc.perform(get("/api/v1/receipts/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirm_returnsConfirmedReceiptWithPromos() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        var confirmResponse = new ConfirmReceiptResponse(
                sampleReceipt(ReceiptStatus.CONFIRMED), List.of());
        when(receiptService.confirm(any(User.class), eq(id), any())).thenReturn(confirmResponse);

        mockMvc.perform(post("/api/v1/receipts/" + id + "/confirm")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.personalPromos").isArray());
    }

    @Test
    void confirm_returns400WhenAlreadyConfirmed() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.confirm(any(User.class), eq(id), any()))
                .thenThrow(new ReceiptNotEditableException("CONFIRMED"));

        mockMvc.perform(post("/api/v1/receipts/" + id + "/confirm")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateItem_returnsUpdatedReceipt() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        var request = new UpdateReceiptItemRequest("ARROZ TIO JOAO 5KG", "7891234567890",
                new BigDecimal("2"), "UN", new BigDecimal("28.90"), new BigDecimal("57.80"), null, null, null, null);
        when(receiptService.updateItem(any(User.class), eq(id), eq(itemId), any(UpdateReceiptItemRequest.class)))
                .thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(patch("/api/v1/receipts/" + id + "/items/" + itemId)
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void reject_returnsRejectedReceipt() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.reject(any(User.class), eq(id))).thenReturn(sampleReceipt(ReceiptStatus.REJECTED));

        mockMvc.perform(post("/api/v1/receipts/" + id + "/reject")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
