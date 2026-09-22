package com.relyon.economizaai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reproduces the production error
 * "Application exception overridden by rollback exception" seen on confirm.
 *
 * <p>{@code ReceiptService.confirm} is {@code @Transactional} and calls the
 * best-effort {@code SavingsAttributionService.attribute}, which is itself
 * {@code @Transactional} (default REQUIRED → joins confirm's transaction). When
 * attribution throws, the inner transactional proxy marks the SHARED transaction
 * rollback-only. confirm then swallows the exception (its contract: "analytics
 * can NEVER break a confirm"), but at commit the outer proxy sees rollback-only
 * and raises {@code UnexpectedRollbackException} — so confirm 500s anyway,
 * defeating the swallow.
 *
 * <p>Unlike {@code ReceiptToPriceIndexIntegrationTest}, this test is NOT
 * {@code @Transactional}: confirm must actually reach its commit boundary for the
 * rollback-only mark to surface. The real {@code attribute} runs through its real
 * proxy — only its collaborator {@code DealSurfaceStateRepository.findAttributable}
 * is mocked to throw, so the failure originates INSIDE the inner transaction
 * exactly as in production (a plain Mockito unit test can't reproduce this because
 * mocking the whole service bypasses its transactional advice).
 */
@SpringBootTest(webEnvironment = MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfirmRollbackOnAttributionFailureIntegrationTest {

    private static final String CHAVE = "43260493015006005182651130003394021410599999";
    // Submit rejects a BARE RS chave (gov.br wall); a scanned RS QR is a full URL.
    private static final String QR_URL = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE + "|2|1";

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private ReceiptIngestionService receiptIngestionService;
    @Autowired private ReceiptRepository receiptRepository;

    @MockitoBean private SefazIngestionService sefazIngestionService;
    @MockitoBean private DealSurfaceStateRepository surfaceStateRepository;

    @BeforeEach
    void stubSefazAndFailingAttribution() {
        when(sefazIngestionService.resolveChave(any())).thenReturn(CHAVE);
        var fetched = new SefazIngestionService.FetchedDocument(
                null, "<html/>", "00000000000000000000000000000000000000000000",
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(any(), any())).thenReturn(fakeParsedReceipt(new BigDecimal("28.00")));
        // Force the best-effort attribution pass to blow up from INSIDE its own
        // @Transactional method, so the shared confirm transaction gets marked
        // rollback-only.
        when(surfaceStateRepository.findAttributable(anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("attribution query exploded"));
    }

    @Test
    void confirm_succeedsEvenWhenAttributionMarksTransactionRollbackOnly() throws Exception {
        var token = registerAndLogin("confirm-rollback@test.com");
        var receiptId = submitAndIngest(token);

        // Best-effort analytics failing must NEVER break confirm — it should still
        // return 200 CONFIRMED. Today it 500s: attribution's rollback-only mark
        // turns confirm's commit into an UnexpectedRollbackException.
        mockMvc.perform(post("/api/v1/receipts/" + receiptId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String submitAndIngest(String token) throws Exception {
        var submitResult = mockMvc.perform(post("/api/v1/receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"" + QR_URL + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var receiptId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asText();
        receiptIngestionService.ingest(UUID.fromString(receiptId), CHAVE);
        return receiptId;
    }

    private String registerAndLogin(String email) throws Exception {
        var body = """
                { "name": "%s", "email": "%s", "password": "password123",
                  "acceptedTermsVersion": "1.0", "acceptedPrivacyVersion": "1.0" }
                """.formatted(email, email);
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private ParsedReceipt fakeParsedReceipt(BigDecimal arrozPrice) {
        return ParsedReceipt.builder()
                .chaveAcesso(CHAVE)
                .cnpjEmitente("93015006005182")
                .marketName("Mercado Teste")
                .issuedAt(LocalDateTime.now())
                .totalAmount(new BigDecimal("100.00"))
                .sourceUrl(null)
                .rawHtml("<html/>")
                .items(List.of(
                        ParsedReceiptItem.builder().lineNumber(1)
                                .rawDescription("ARROZ TIO J 5KG").ean("789001")
                                .quantity(BigDecimal.ONE).unit("UN")
                                .unitPrice(arrozPrice).totalPrice(arrozPrice).build()
                ))
                .build();
    }
}
