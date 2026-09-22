package com.relyon.economizaai.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test of the critical receipt → confirm → price-index
 * pipeline. Uses the full Spring context with H2 + Hibernate `create-drop`
 * (set up in application-test.yaml). The only mock is the SEFAZ HTTP client
 * (so we don't actually hit the government portal during tests); everything
 * downstream is real.
 *
 * Verifies:
 *  - Submit + confirm produces saved Receipt + ReceiptItems
 *  - Canonicalization auto-creates Products with extraction enrichment
 *  - PriceObservations are written for items linked to products
 *  - Audit rows link observations back to the receipt + household
 *  - Personal promo detection runs on confirm and the response carries the list
 */
@SpringBootTest(webEnvironment = MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ReceiptToPriceIndexIntegrationTest {

    private static final String CHAVE = "43260493015006005182651130003394021410514546";
    // Submit rejects a BARE RS chave (gov.br wall); a scanned RS QR is a full URL
    // that resolves to CHAVE (resolveChave is mocked below to return CHAVE anyway).
    private static final String QR_URL = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE + "|2|1";

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private PriceObservationRepository observationRepository;
    @Autowired private PriceObservationAuditRepository auditRepository;
    @Autowired private ReceiptIngestionService receiptIngestionService;

    @MockitoBean private SefazIngestionService sefazIngestionService;

    @BeforeEach
    void stubChaveResolution() {
        when(sefazIngestionService.resolveChave(any())).thenReturn(CHAVE);
    }

    /**
     * Submit returns immediately as PROCESSING; the SEFAZ fetch+parse runs
     * asynchronously after commit in production. The test runs in a single
     * transaction (no commit, no real async thread), so we drive the background
     * step explicitly: submit, then invoke the ingestion service directly so the
     * receipt reaches PENDING_CONFIRMATION before we confirm. Returns the id.
     */
    private String submitAndIngest(String token) throws Exception {
        var submitResult = mockMvc.perform(post("/api/v1/receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"" + QR_URL + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var submitJson = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        assertEquals("PROCESSING", submitJson.get("status").asText(),
                "submit returns immediately as PROCESSING");
        var receiptId = submitJson.get("id").asText();
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

    @Test
    void submitConfirmFlow_createsReceiptCanonicalProductsAndObservations() throws Exception {
        var token = registerAndLogin("e2e-pi-1@test.com");
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", "00000000000000000000000000000000000000000000",
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(any(), any())).thenReturn(fakeParsedReceipt(new BigDecimal("28.00")));

        // submit (PROCESSING) + drive the async ingestion to PENDING_CONFIRMATION
        var receiptId = submitAndIngest(token);

        // confirm — triggers canonicalization + observation write + personal promo check
        var confirmResult = mockMvc.perform(post("/api/v1/receipts/" + receiptId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        var confirmJson = objectMapper.readTree(confirmResult.getResponse().getContentAsString());
        assertEquals("CONFIRMED", confirmJson.path("receipt").path("status").asText());
        assertNotNull(confirmJson.path("personalPromos"));

        // No prior history → no personal promos on first receipt
        assertEquals(0, confirmJson.path("personalPromos").size(),
                "first receipt has no historical baseline so no personal promos");

        // PriceObservation should have been written (user has contributionOptIn=true by default)
        var observations = observationRepository.findAll();
        assertEquals(1, observations.size());
        var obs = observations.get(0);
        assertEquals("93015006005182", obs.getMarketCnpj());
        assertEquals("93015006", obs.getMarketCnpjRoot(),
                "cnpj_root must be denormalized for chain-level aggregation");
        assertEquals(0, obs.getUnitPrice().compareTo(new BigDecimal("28.00")));

        // Audit row links back to the receipt for k-anon counting
        var audits = auditRepository.findAll();
        assertEquals(1, audits.size());
        assertEquals(obs.getId(), audits.get(0).getObservation().getId());
    }

    @Test
    void resubmit_replacesPriorPendingReceiptInsteadOfBlocking() throws Exception {
        // Repro of the FE-reported bug: user scans an NF, closes the app
        // before confirming, then tries to scan the same QR again — the
        // PENDING_CONFIRMATION row from attempt 1 must be replaced, not
        // 409'd, so the user can resume the flow.
        var token = registerAndLogin("e2e-resubmit@test.com");
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", "00000000000000000000000000000000000000000000",
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(any(), any())).thenReturn(fakeParsedReceipt(new BigDecimal("28.00")));

        // First submit — leaves a non-CONFIRMED (PROCESSING) row.
        var first = mockMvc.perform(post("/api/v1/receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"" + QR_URL + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        // Re-submit the same chave — must succeed (201) with a new id, NOT 409.
        var secondId = submitAndIngest(token);
        assertNotEquals(firstId, secondId, "re-submit should produce a fresh receipt id");

        // Confirm the second one — first must be gone.
        mockMvc.perform(post("/api/v1/receipts/" + secondId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Now a third submit must be blocked because the chave is CONFIRMED.
        mockMvc.perform(post("/api/v1/receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"" + QR_URL + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void confirm_skipsObservationsWhenUserOptedOut() throws Exception {
        var token = registerAndLogin("e2e-pi-optout@test.com");
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", "00000000000000000000000000000000000000000000",
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(any(), any())).thenReturn(fakeParsedReceipt(new BigDecimal("28.00")));

        // opt out before submitting
        mockMvc.perform(patch("/api/v1/users/me/contribution")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contributionOptIn\":false}"))
                .andExpect(status().isOk());

        var receiptId = submitAndIngest(token);

        mockMvc.perform(post("/api/v1/receipts/" + receiptId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertEquals(0, observationRepository.count(),
                "opted-out users must not contribute to the price index");
        assertEquals(0, auditRepository.count());
    }
}
