package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ExperimentalStateFailedException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.enums.MerchantSegment;
import com.relyon.economizai.service.extraction.EanCatalogEnrichmentService;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.geo.MerchantSupportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptIngestionServiceTest {

    private static final String CHAVE_RS = "43250612345678000190650010000012341000012345";
    private static final String QR = CHAVE_RS;

    @Mock private ReceiptRepository receiptRepository;
    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private EanCatalogEnrichmentService eanCatalogEnrichmentService;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MerchantSupportGate merchantSupportGate;

    @InjectMocks private ReceiptIngestionService service;

    @BeforeEach
    void runTransactionCallbacksInline() {
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Receipt processingReceipt() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
        return Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(household)
                .chaveAcesso(CHAVE_RS)
                .uf(UnidadeFederativa.RS)
                .qrPayload(QR)
                .status(ReceiptStatus.PROCESSING)
                .build();
    }

    private ParsedReceipt sampleParsed() {
        return ParsedReceipt.builder()
                .chaveAcesso(CHAVE_RS)
                .cnpjEmitente("12345678000190")
                .marketName("Mercado X")
                .marketAddress("Rua Y, 123")
                .issuedAt(LocalDateTime.of(2026, Month.APRIL, 15, 18, 0))
                .totalAmount(new BigDecimal("57.80"))
                .sourceUrl(null)
                .rawHtml("<html/>")
                .items(List.of(ParsedReceiptItem.builder()
                        .lineNumber(1)
                        .rawDescription("ARROZ TIO J 5KG")
                        .ean("7891234567890")
                        .quantity(new BigDecimal("2"))
                        .unit("UN")
                        .unitPrice(new BigDecimal("28.90"))
                        .totalPrice(new BigDecimal("57.80"))
                        .build()))
                .build();
    }

    @Test
    void ingest_success_fillsItemsAndMarksPendingConfirmation() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenReturn(sampleParsed());

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, receipt.getStatus());
        assertEquals(1, receipt.getItems().size());
        assertEquals("ARROZ TIO J 5KG", receipt.getItems().get(0).getRawDescription());
        assertEquals("Mercado X", receipt.getMarketName());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_parseFailure_marksFailedParseWithReason() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenThrow(new ReceiptParseException("no items found"));

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_experimentalStateBlocked_deviceCapableClient_marksNeedsDeviceFetch() {
        // Server has no adapter for the state and its portal blocks our datacenter IP
        // (Pernambuco): recoverable — flag for a DEVICE-CAPABLE app to retry on-device.
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenThrow(new ExperimentalStateFailedException("PE"));

        service.ingest(receipt.getId(), QR, true);

        assertEquals(ReceiptStatus.NEEDS_DEVICE_FETCH, receipt.getStatus());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_experimentalStateBlocked_oldMobileApp_marksFailedParseWithAppUpdateReason() {
        // An older mobile app (didn't send X-Device-Fetch) can't resolve NEEDS_DEVICE_FETCH —
        // it gets FAILED_PARSE with an "update the app" reason, not a generic failure.
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenThrow(new ExperimentalStateFailedException("PE"));

        service.ingest(receipt.getId(), QR, false); // mobile, not device-capable

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
        assertEquals("receipt.state.app_update_required", receipt.getParseErrorReason());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_experimentalStateBlocked_webPhoto_marksFailedParseGenericReason() {
        // Web / photo upload (2-arg) isn't "the app" — a blocked state is a generic FAILED_PARSE,
        // NOT an "update the app" message.
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenThrow(new ExperimentalStateFailedException("PE"));

        service.ingest(receipt.getId(), QR); // 2-arg = web/photo

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
        assertNotEquals("receipt.state.app_update_required", receipt.getParseErrorReason());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingestPrefetched_experimentalStateFailed_marksFailedParseNoLoop() {
        // The DEVICE content also couldn't be parsed — a real dead end, NOT another
        // NEEDS_DEVICE_FETCH (which would loop the app forever).
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<xml/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fromClientContent(any(), eq(CHAVE_RS), eq(UnidadeFederativa.RS), any()))
                .thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenThrow(new ExperimentalStateFailedException("PE"));

        service.ingestPrefetched(receipt.getId(), QR, "<xml/>");

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
    }

    @Test
    void ingest_blockedMerchant_marksFailedWithoutStoringItemsOrHtml() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        var barMarket = MarketLocation.builder()
                .cnpj("12345678000190").cnpjRoot("12345678")
                .segment(MerchantSegment.FOOD_SERVICE).build();
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenReturn(sampleParsed());
        when(marketLocationService.resolveForIngest("12345678000190", "Mercado X", "Rua Y, 123"))
                .thenReturn(barMarket);
        when(merchantSupportGate.isBlocked(barMarket)).thenReturn(true);

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
        assertEquals("receipt.merchant.unsupported:", receipt.getParseErrorReason());
        // store-nothing tombstone: market identification only
        assertEquals("12345678000190", receipt.getCnpjEmitente());
        assertEquals("Mercado X", receipt.getMarketName());
        assertTrue(receipt.getItems().isEmpty());
        assertNull(receipt.getRawHtml());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_supportedMerchant_proceedsNormally() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        var market = MarketLocation.builder()
                .cnpj("12345678000190").cnpjRoot("12345678")
                .segment(MerchantSegment.SUPERMARKET).build();
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenReturn(sampleParsed());
        when(marketLocationService.resolveForIngest(any(), any(), any())).thenReturn(market);
        when(merchantSupportGate.isBlocked(market)).thenReturn(false);

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, receipt.getStatus());
    }

    @Test
    void ingest_fetchThrows_marksFailedParse() {
        var receipt = processingReceipt();
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(eq(QR), any())).thenThrow(new RuntimeException("captcha timeout"));

        service.ingest(receipt.getId(), QR);

        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        assertEquals(ReceiptStatus.FAILED_PARSE, captor.getValue().getStatus());
    }

    @Test
    void ingest_transientDbTermination_leavesReceiptProcessingForRetry() {
        // Reproduces the live error:
        //   org.postgresql.util.PSQLException: FATAL: terminating connection due to administrator command
        // SQLState 57P01 — an admin-initiated / transient connection drop (DB restart,
        // idle-in-transaction timeout, pg_terminate_backend). Spring translates it to a
        // TransientDataAccessResourceException, which is a RuntimeException. The receipt
        // itself is perfectly valid — a retry seconds later would succeed — so the row
        // must stay PROCESSING for the sweeper/retry, NOT be poisoned to FAILED_PARSE
        // (which forces the user to needlessly rescan a good receipt).
        var receipt = processingReceipt();
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        var pgTermination = new PSQLException(
                "FATAL: terminating connection due to administrator command",
                PSQLState.CONNECTION_FAILURE);
        when(sefazIngestionService.fetch(eq(QR), any()))
                .thenThrow(new TransientDataAccessResourceException("connection lost", pgTermination));

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.PROCESSING, receipt.getStatus());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void ingest_skipsWhenReceiptNoLongerProcessing() {
        var receipt = processingReceipt();
        receipt.setStatus(ReceiptStatus.CONFIRMED);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        service.ingest(receipt.getId(), QR);

        verify(sefazIngestionService, never()).fetch(any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void ingest_discardsResultWhenStatusChangedDuringFetch() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        // First read (pre-fetch guard) sees PROCESSING; second read (persist) sees CONFIRMED —
        // the user deleted/resubmitted while the fetch was in flight.
        var confirmed = processingReceipt();
        confirmed.setStatus(ReceiptStatus.CONFIRMED);
        when(receiptRepository.findById(receipt.getId()))
                .thenReturn(Optional.of(receipt))
                .thenReturn(Optional.of(confirmed));
        when(sefazIngestionService.fetch(eq(QR), any())).thenReturn(fetched);
        when(sefazIngestionService.parse(eq(fetched), any())).thenReturn(sampleParsed());

        service.ingest(receipt.getId(), QR);

        verify(receiptRepository, never()).save(any());
    }

    @Test
    void markFailed_transitionsProcessingReceipt() {
        var receipt = processingReceipt();
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        service.markFailed(receipt.getId(), new RuntimeException("pool rejected"));

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
        assertTrue(receipt.getParseErrorReason().contains("RuntimeException"));
        verify(receiptRepository).save(receipt);
    }

    @Test
    void markFailed_leavesNonProcessingReceiptAlone() {
        var receipt = processingReceipt();
        receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        service.markFailed(receipt.getId(), new RuntimeException("late failure"));

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, receipt.getStatus());
        verify(receiptRepository, never()).save(any());
    }
}
