package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import com.relyon.economizaai.exception.PhotoExtractionUnavailableException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptService;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.scan.PhotoUploadValidator;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fluxo ASSÍNCRONO: {@code extract} cria a nota em PROCESSING e retorna; o trabalho de visão
 * roda em {@code processPhotosAsync} (inline aqui, pois {@code self == this} sem proxy Spring).
 * O estado FINAL da nota é o que importa (PENDING_CONFIRMATION no sucesso, FAILED_PARSE no erro).
 */
@ExtendWith(MockitoExtension.class)
class PhotoReceiptExtractionServiceTest {

    @Mock private AiGateway aiGateway;
    @Mock private PhotoUploadValidator photoUploadValidator;
    @Mock private PaidApiGuardService paidApiGuard;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptService receiptService;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PhotoReceiptExtractionService service;
    private User user;
    private Receipt savedReceipt;

    @BeforeEach
    void setUp() {
        service = new PhotoReceiptExtractionService(aiGateway, photoUploadValidator, paidApiGuard,
                subscriptionGate, receiptRepository, receiptService, transactionTemplate, true, 60, 6);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("alexandre+photo@economizaai.app")
                .household(household).build();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        // save: guarda a nota (dá id na 1ª vez) e devolve-a; findById devolve a mesma.
        lenient().when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> {
            var receipt = invocation.<Receipt>getArgument(0);
            if (receipt.getId() == null) receipt.setId(UUID.randomUUID());
            savedReceipt = receipt;
            return receipt;
        });
        lenient().when(receiptRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedReceipt));
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "nota.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    void extract_finalizesPendingPhotoReceipt() {
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.extractorModel()).thenReturn("claude-haiku");
        when(aiGateway.completeWithImage(any(), anyString(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("""
                        {"is_receipt": true, "market_name": "Bar do Ze", "cnpj": "12345678000199",
                         "issued_at": "2026-07-15 15:45", "total_amount": 47.00,
                         "items": [
                           {"description": "Chopp Brahma 440ml", "quantity": 1, "unit": "UN",
                            "unit_price": 14.00, "total_price": 14.00},
                           {"description": "Bandeja de Pasteis", "quantity": 1, "unit": "UN",
                            "unit_price": 33.00, "total_price": 33.00}]}""");

        var receiptId = service.extract(user, photo());

        assertThat(receiptId).isNotNull();
        assertThat(savedReceipt.getOrigin()).isEqualTo(ReceiptOrigin.PHOTO);
        assertThat(savedReceipt.getStatus()).isEqualTo(ReceiptStatus.PENDING_CONFIRMATION);
        assertThat(savedReceipt.getChaveAcesso()).startsWith("PH").hasSize(44);
        assertThat(savedReceipt.getCnpjEmitente()).isEqualTo("12345678000199");
        assertThat(savedReceipt.getItems()).hasSize(2);
        assertThat(savedReceipt.getTotalAmount()).isEqualByComparingTo("47.00");
        verify(receiptService).enforceMonthlyReceiptCap(user);
        verify(paidApiGuard, atLeastOnce()).assertWithinDailyCap(user.getId(), PaidApiService.LLM_VISION);
        verify(paidApiGuard).recordSuccess(user.getId(), PaidApiService.LLM_VISION, null, "anthropic");
    }

    @Test
    void extract_nonReceiptImage_marksReceiptFailed() {
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.extractorModel()).thenReturn("claude-haiku");
        when(aiGateway.completeWithImage(any(), anyString(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{\"is_receipt\": false}");

        service.extract(user, photo());

        assertThat(savedReceipt.getStatus()).isEqualTo(ReceiptStatus.FAILED_PARSE);
        assertThat(savedReceipt.getParseErrorReason()).isEqualTo("receipt.photo.not-receipt");
    }

    @Test
    void extract_sevenPhotos_rejectedBeforeAnyWorkWithTheLimitAsArgument() {
        when(aiGateway.isEnabled()).thenReturn(true);
        List<MultipartFile> sevenPhotos = Collections.nCopies(7, photo());

        var rejection = assertThrows(InvalidReceiptPhotoException.class, () -> service.extract(user, sevenPhotos));

        assertThat(rejection.getMessageKey()).isEqualTo("receipt.photo.too-many");
        assertThat(rejection.getArguments()).containsExactly("6");
        verify(subscriptionGate, never()).require(any(), any());
    }

    @Test
    void extract_sixPhotos_passesTheCountCheck() {
        when(aiGateway.isEnabled()).thenReturn(true);
        List<MultipartFile> sixPhotos = Collections.nCopies(6, photo());
        // Para o fluxo logo após a checagem de quantidade: chegar no gate = 6 fotos aceitas.
        doThrow(new IllegalStateException("past-count-check")).when(subscriptionGate).require(any(), any());

        var stop = assertThrows(IllegalStateException.class, () -> service.extract(user, sixPhotos));

        assertThat(stop.getMessage()).isEqualTo("past-count-check");
    }

    @Test
    void extract_disabledFlag_returns503Semantics() {
        var disabled = new PhotoReceiptExtractionService(aiGateway, photoUploadValidator, paidApiGuard,
                subscriptionGate, receiptRepository, receiptService, transactionTemplate, false, 60, 6);

        assertThrows(PhotoExtractionUnavailableException.class, () -> disabled.extract(user, photo()));
        verify(aiGateway, never()).completeWithImage(any(), anyString(), anyString(), anyString(), any(), anyString(), anyInt(), any());
    }

    @Test
    void extract_llmFailure_marksReceiptFailed() {
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.extractorModel()).thenReturn("claude-haiku");
        when(aiGateway.completeWithImage(any(), anyString(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenThrow(new AiGateway.AiUnavailableException("timeout"));

        service.extract(user, photo());

        verify(paidApiGuard).recordFailure(user.getId(), PaidApiService.LLM_VISION, null, "anthropic");
        assertThat(savedReceipt.getStatus()).isEqualTo(ReceiptStatus.FAILED_PARSE);
        assertThat(savedReceipt.getParseErrorReason()).isEqualTo("receipt.photo.extraction-failed");
    }
}
