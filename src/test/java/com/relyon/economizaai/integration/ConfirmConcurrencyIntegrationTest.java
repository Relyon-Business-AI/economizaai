package com.relyon.economizaai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.ReceiptNotEditableException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.ReceiptService;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two confirms of the same nota (double tap, client retry, or the auto-confirm sweeper)
 * must produce ONE set of price observations. NOT {@code @Transactional}: the two callers
 * need their own real transactions for the row lock to mean anything.
 */
@SpringBootTest(webEnvironment = MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfirmConcurrencyIntegrationTest {

    private static final long FIRST_CONFIRM_HOLD_MS = 400;

    @Autowired private MockMvc mockMvc;
    @Autowired private ReceiptIngestionService receiptIngestionService;
    @Autowired private ReceiptService receiptService;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PriceObservationAuditRepository auditRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private PriceObservationRepository observationRepository;
    private final List<UUID> committedReceiptIds = new ArrayList<>();

    @MockitoBean private SefazIngestionService sefazIngestionService;

    /** This test commits for real; other suites count observations globally, so leave none behind. */
    @AfterEach
    void removeCommittedObservations() {
        for (var receiptId : committedReceiptIds) {
            var audits = auditRepository.findByReceiptId(receiptId);
            var observations = audits.stream().map(PriceObservationAudit::getObservation).toList();
            auditRepository.deleteAll(audits);
            observationRepository.deleteAll(observations);
        }
    }

    @Test
    void secondConfirm_waitsForTheFirstAndGetsAlreadyConfirmedWithoutContributingAgain() throws Exception {
        var chave = "43260493015006005182651130003394021410577771";
        var owner = registerUser("e2e-confirm-race-1@economizaai.app");
        // The product and its alias already exist (an earlier nota with the same item), so the
        // racing confirms have nothing to create — only the row lock keeps them apart.
        var earlierReceiptId = submitAndIngest(owner.token(), "43260493015006005182651130003394021410577770", "789101");
        receiptService.confirm(owner.user(), earlierReceiptId, null);
        var receiptId = submitAndIngest(owner.token(), chave, "789101");
        var firstConfirmDone = new CountDownLatch(1);
        var firstCaller = Executors.newSingleThreadExecutor();
        try {
            // First caller: confirms, then keeps its transaction open (uncommitted) for a moment.
            var firstOutcome = firstCaller.submit(() -> transactionTemplate.execute(txStatus -> {
                receiptService.confirm(owner.user(), receiptId, null);
                firstConfirmDone.countDown();
                sleepQuietly(FIRST_CONFIRM_HOLD_MS);
                return null;
            }));
            assertTrue(firstConfirmDone.await(10, TimeUnit.SECONDS));

            // Second caller arrives while the first is still uncommitted.
            assertThrows(ReceiptNotEditableException.class,
                    () -> receiptService.confirm(owner.user(), receiptId, null));
            firstOutcome.get(10, TimeUnit.SECONDS);
        } finally {
            firstCaller.shutdownNow();
        }

        assertEquals(ReceiptStatus.CONFIRMED, receiptRepository.findById(receiptId).orElseThrow().getStatus());
        assertEquals(1, auditRepository.findByReceiptId(receiptId).size(),
                "one item → exactly one observation, however many confirms raced");
    }

    @Test
    void confirmBatch_aFailingReceiptIsSkippedAndTheOthersStillCommit() throws Exception {
        var chave = "43260493015006005182651130003394021410577772";
        var owner = registerUser("e2e-confirm-race-2@economizaai.app");
        var receiptId = submitAndIngest(owner.token(), chave, "789102");

        var confirmed = receiptService.confirmBatch(owner.user(), List.of(UUID.randomUUID(), receiptId));

        assertEquals(1, confirmed);
        assertEquals(ReceiptStatus.CONFIRMED, receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    private record RegisteredUser(String token, User user) {}

    private RegisteredUser registerUser(String email) throws Exception {
        var body = """
                { "name": "%s", "email": "%s", "password": "password123",
                  "acceptedTermsVersion": "1.1", "acceptedPrivacyVersion": "1.1" }
                """.formatted(email, email);
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        var token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        return new RegisteredUser(token, userRepository.findByEmail(email).orElseThrow());
    }

    private UUID submitAndIngest(String token, String chave, String ean) throws Exception {
        when(sefazIngestionService.resolveChave(any())).thenReturn(chave);
        var fetched = new SefazIngestionService.FetchedDocument(
                null, "<html/>", "00000000000000000000000000000000000000000000", UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(any(), any())).thenReturn(parsedReceipt(chave, ean));
        var qrUrl = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + chave + "|2|1";
        var submitResult = mockMvc.perform(post("/api/v1/receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"" + qrUrl + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var receiptId = UUID.fromString(
                objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asText());
        receiptIngestionService.ingest(receiptId, chave);
        committedReceiptIds.add(receiptId);
        return receiptId;
    }

    private ParsedReceipt parsedReceipt(String chave, String ean) {
        return ParsedReceipt.builder()
                .chaveAcesso(chave)
                .cnpjEmitente("93015006005182")
                .marketName("Mercado Teste")
                .issuedAt(LocalDateTime.now())
                .totalAmount(new BigDecimal("28.00"))
                .sourceUrl(null)
                .rawHtml("<html/>")
                .items(List.of(
                        ParsedReceiptItem.builder().lineNumber(1)
                                .rawDescription("FEIJAO PRETO TESTE 1KG").ean(ean)
                                .quantity(BigDecimal.ONE).unit("UN")
                                .unitPrice(new BigDecimal("28.00")).totalPrice(new BigDecimal("28.00")).build()
                ))
                .build();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
