package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoReceiptExtractionServiceTest {

    @Mock private OpenAiClient openAiClient;
    @Mock private PhotoUploadValidator photoUploadValidator;
    @Mock private PaidApiGuardService paidApiGuard;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptService receiptService;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PhotoReceiptExtractionService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PhotoReceiptExtractionService(openAiClient, photoUploadValidator, paidApiGuard,
                subscriptionGate, receiptRepository, receiptService, transactionTemplate, true, 60);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("alexandre+photo@economizaai.app")
                .household(household).build();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> {
            var receipt = invocation.<Receipt>getArgument(0);
            receipt.setId(UUID.randomUUID());
            return receipt;
        });
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "nota.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    void extract_buildsPendingPhotoReceiptExcludedFromIndexPath() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJsonWithImage(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(objectMapper.readTree("""
                        {"is_receipt": true, "market_name": "Bar do Ze", "issued_at": "2026-07-15 15:45",
                         "total_amount": 47.00,
                         "items": [
                           {"description": "Chopp Brahma 440ml", "quantity": 1, "unit": "UN",
                            "unit_price": 14.00, "total_price": 14.00},
                           {"description": "Bandeja de Pasteis", "quantity": 1, "unit": "UN",
                            "unit_price": 33.00, "total_price": 33.00}]}"""));

        var receiptId = service.extract(user, photo());

        assertThat(receiptId).isNotNull();
        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        var receipt = captor.getValue();
        assertThat(receipt.getOrigin()).isEqualTo(ReceiptOrigin.PHOTO);
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.PENDING_CONFIRMATION);
        assertThat(receipt.getChaveAcesso()).startsWith("PH").hasSize(44);
        assertThat(receipt.getItems()).hasSize(2);
        assertThat(receipt.getTotalAmount()).isEqualByComparingTo("47.00");
        verify(receiptService).enforceMonthlyReceiptCap(user);
        verify(paidApiGuard).assertWithinDailyCap(user.getId(), PaidApiService.LLM_VISION);
        verify(paidApiGuard).recordSuccess(user.getId(), PaidApiService.LLM_VISION, null, "openai");
    }

    @Test
    void extract_rejectsNonReceiptImages() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJsonWithImage(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(objectMapper.readTree("{\"is_receipt\": false}"));

        assertThrows(InvalidReceiptPhotoException.class, () -> service.extract(user, photo()));
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void extract_disabledFlag_returns503Semantics() {
        var disabled = new PhotoReceiptExtractionService(openAiClient, photoUploadValidator, paidApiGuard,
                subscriptionGate, receiptRepository, receiptService, transactionTemplate, false, 60);

        assertThrows(PhotoExtractionUnavailableException.class, () -> disabled.extract(user, photo()));
        verify(openAiClient, never()).completeJsonWithImage(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void extract_llmFailure_recordsFailureAndSurfacesUnavailable() {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJsonWithImage(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenThrow(new LlmCallFailedException("timeout"));

        assertThrows(PhotoExtractionUnavailableException.class, () -> service.extract(user, photo()));
        verify(paidApiGuard).recordFailure(user.getId(), PaidApiService.LLM_VISION, null, "openai");
    }
}
