package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiItemCorrectionServiceTest {

    @Mock private AiGateway aiGateway;
    @Mock private ProductRepository productRepository;
    @Mock private CategoryResolver categoryResolver;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private AiFindingRepository findingRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private PaidApiGuardService paidApiGuard;

    private AiItemCorrectionService service;

    private final UUID receiptId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private ReceiptItem item;

    @BeforeEach
    void setUp() {
        service = new AiItemCorrectionService(aiGateway, productRepository, categoryResolver, receiptItemRepository,
                findingRepository, transactionTemplate, paidApiGuard);
        var receipt = Receipt.builder().id(receiptId).household(Household.builder().id(householdId).build()).build();
        item = ReceiptItem.builder().id(itemId).receipt(receipt).rawDescription("ARROZ CAMIL 5KG").build();
        lenient().when(aiGateway.isEnabled()).thenReturn(true);
        lenient().when(aiGateway.extractorModel()).thenReturn("model");
        lenient().when(receiptItemRepository.findByIdWithReceiptAndHousehold(itemId)).thenReturn(Optional.of(item));
        lenient().when(receiptItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        lenient().when(findingRepository.findByStatus(any())).thenReturn(List.of());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void correction_isGuardedAttributedAndLedgeredAsCategorizationSpend() {
        when(aiGateway.complete(eq(AiActivity.ITEM_CORRECTION), eq("model"), anyString(), anyString(), anyInt(), eq(userId)))
                .thenReturn("{\"genericName\":\"Arroz Branco\",\"brand\":\"Camil\",\"category\":\"GROCERIES\",\"confidence\":0.9}");

        service.correct(receiptId, itemId, householdId, userId, "é arroz");

        verify(paidApiGuard).assertUnderDailyBudget(PaidApiService.LLM_ENRICH);
        verify(paidApiGuard).assertWithinDailyCap(userId, PaidApiService.LLM_ENRICH);
        verify(paidApiGuard).recordSuccess(userId, PaidApiService.LLM_ENRICH, null, "ai");
        assertThat(item.getProduct().getGenericName()).isEqualTo("Arroz Branco");
    }

    @Test
    void userCapReached_failsWithLocalized429_beforeSpending() {
        doThrow(new PaidApiQuotaExceededException("LLM_ENRICH"))
                .when(paidApiGuard).assertWithinDailyCap(userId, PaidApiService.LLM_ENRICH);

        assertThatThrownBy(() -> service.correct(receiptId, itemId, householdId, userId, "é arroz"))
                .isInstanceOf(PaidApiQuotaExceededException.class)
                .hasFieldOrPropertyWithValue("messageKey", "ai.correction.limit_reached");

        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(paidApiGuard, never()).recordSuccess(any(), any(), any(), any());
    }

    @Test
    void dailyBudgetSpent_failsWithTheSameLocalized429() {
        doThrow(new PaidApiBudgetExceededException())
                .when(paidApiGuard).assertUnderDailyBudget(PaidApiService.LLM_ENRICH);

        assertThatThrownBy(() -> service.correct(receiptId, itemId, householdId, userId, "é arroz"))
                .isInstanceOf(PaidApiQuotaExceededException.class)
                .hasFieldOrPropertyWithValue("messageKey", "ai.correction.limit_reached");
    }

    @Test
    void foreignItem_isRejectedBeforeAnyGuardOrSpend() {
        assertThatThrownBy(() -> service.correct(receiptId, itemId, UUID.randomUUID(), userId, "é arroz"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(paidApiGuard, never()).assertWithinDailyCap(any(), any());
    }
}
