package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiItemFallbackServiceTest {

    private static final String CLASSIFICATION_JSON =
            "[{\"n\":1,\"genericName\":\"Arroz Branco\",\"brand\":\"Camil\",\"category\":\"GROCERIES\",\"confidence\":0.9}]";

    @Mock private AiGateway aiGateway;
    @Mock private CategoryResolver categoryResolver;
    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private AiFindingRepository findingRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private PaidApiGuardService paidApiGuard;

    private AiItemFallbackService service;

    private final UUID receiptId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private ReceiptItem item;

    @BeforeEach
    void setUp() {
        service = new AiItemFallbackService(aiGateway, productRepository, categoryResolver, receiptItemRepository,
                findingRepository, transactionTemplate, paidApiGuard);
        item = ReceiptItem.builder().id(itemId).rawDescription("ARROZ CAMIL 5KG").build();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().when(aiGateway.isEnabled()).thenReturn(true);
        lenient().when(aiGateway.extractorModel()).thenReturn("model");
        lenient().when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(CLASSIFICATION_JSON);
        lenient().when(receiptItemRepository.findUnmatchedByReceiptId(receiptId)).thenReturn(List.of(item));
        lenient().when(receiptItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    }

    @Test
    void classificationSurvivesFindingSaveFailure() {
        when(findingRepository.existsByTypeAndStatusAndTitleStartingWith(
                eq(AiFindingType.MISSING_RULE), eq(AiFindingStatus.PENDING), anyString())).thenReturn(false);
        when(findingRepository.save(any(AiFinding.class))).thenThrow(new RuntimeException("constraint violation"));

        assertThatCode(() -> service.applyFallback(receiptId, userId)).doesNotThrowAnyException();

        verify(productRepository).save(any(Product.class));
        verify(receiptItemRepository).save(item);
        assertThat(item.getProduct()).isNotNull();
        assertThat(item.getProduct().getGenericName()).isEqualTo("Arroz Branco");
        assertThat(item.getCategoryAtConfirmation()).isEqualTo(ProductCategory.GROCERIES);
    }

    @Test
    void findingPersistsWithoutSweepRunId() {
        when(findingRepository.existsByTypeAndStatusAndTitleStartingWith(
                eq(AiFindingType.MISSING_RULE), eq(AiFindingStatus.PENDING), anyString())).thenReturn(false);

        service.applyFallback(receiptId, userId);

        var findingCaptor = ArgumentCaptor.forClass(AiFinding.class);
        verify(findingRepository).save(findingCaptor.capture());
        var finding = findingCaptor.getValue();
        assertThat(finding.getSweepRunId()).isNull();
        assertThat(finding.getType()).isEqualTo(AiFindingType.MISSING_RULE);
        assertThat(finding.getStatus()).isEqualTo(AiFindingStatus.PENDING);
        assertThat(finding.getTitle()).isEqualTo("Regra: \"ARROZ CAMIL 5KG\" → Arroz Branco");
        assertThat(finding.getPayload()).contains("ARROZ CAMIL 5KG");
    }

    @Test
    void dedupeSkipsFindingWhenSamePendingSuggestionExists() {
        when(findingRepository.existsByTypeAndStatusAndTitleStartingWith(
                AiFindingType.MISSING_RULE, AiFindingStatus.PENDING, "Regra: \"ARROZ CAMIL 5KG\" →"))
                .thenReturn(true);

        service.applyFallback(receiptId, userId);

        verify(findingRepository, never()).save(any(AiFinding.class));
        assertThat(item.getProduct()).isNotNull();
    }

    @Test
    void skipsFindingWhenItemAlreadyMatched() {
        item.setProduct(Product.builder().id(UUID.randomUUID()).build());

        service.applyFallback(receiptId, userId);

        // Re-checks BEFORE the LLM call — must not even pay for the classification.
        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt());
        verify(productRepository, never()).save(any(Product.class));
        verify(findingRepository, never()).save(any(AiFinding.class));
    }

    @Test
    void reprocessUnmatched_runsFallbackOverReceiptsAndTalliesOutcomes() {
        when(findingRepository.existsByTypeAndStatusAndTitleStartingWith(
                eq(AiFindingType.MISSING_RULE), eq(AiFindingStatus.PENDING), anyString())).thenReturn(false);
        when(receiptItemRepository.findReceiptIdsWithUnmatchedItems(any())).thenReturn(List.of(receiptId));

        var tally = service.reprocessUnmatched(10);

        assertThat(tally.matched()).isEqualTo(1);
        assertThat(item.getProduct()).isNotNull();
    }

    private ReceiptItem unmatchedItem(String description) {
        var unmatched = ReceiptItem.builder().id(UUID.randomUUID()).rawDescription(description).build();
        lenient().when(receiptItemRepository.findById(unmatched.getId())).thenReturn(Optional.of(unmatched));
        return unmatched;
    }

    @Test
    void allUnmatchedItems_goInOneCall_withOneLedgerRow_attributedToTheUser() {
        var rice = unmatchedItem("ARROZ CAMIL 5KG");
        var soda = unmatchedItem("REFRIG COCA 2L");
        var soap = unmatchedItem("SAB PROTEX 85G");
        when(receiptItemRepository.findUnmatchedByReceiptId(receiptId)).thenReturn(List.of(rice, soda, soap));
        when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt(), any())).thenReturn("""
                [{"n":1,"genericName":"Arroz Branco","brand":"Camil","category":"GROCERIES","confidence":0.9},
                 {"n":2,"genericName":"Refrigerante de Cola","brand":"Coca-Cola","category":"BEVERAGES","confidence":0.9},
                 {"n":3,"genericName":"Sabonete","brand":"Protex","category":"PERSONAL_CARE","confidence":0.8}]
                """);

        service.applyFallback(receiptId, userId);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiGateway, times(1)).complete(eq(AiActivity.ITEM_CLASSIFY), eq("model"), anyString(),
                promptCaptor.capture(), anyInt(), eq(userId));
        assertThat(promptCaptor.getValue())
                .contains("1. \"ARROZ CAMIL 5KG\"", "2. \"REFRIG COCA 2L\"", "3. \"SAB PROTEX 85G\"");
        verify(paidApiGuard, times(1)).recordSuccess(userId, PaidApiService.LLM_ENRICH, null, "ai");
        verify(paidApiGuard).assertWithinDailyCap(userId, PaidApiService.LLM_ENRICH);
        assertThat(rice.getProduct().getGenericName()).isEqualTo("Arroz Branco");
        assertThat(soda.getProduct().getCategory()).isEqualTo(ProductCategory.BEVERAGES);
        assertThat(soap.getProduct().getBrand()).isEqualTo("Protex");
    }

    @Test
    void itemMissingOrMalformedInTheBatchedAnswer_staysUnmatched_othersStillApply() {
        var rice = unmatchedItem("ARROZ CAMIL 5KG");
        var mystery = unmatchedItem("XPTO 123");
        var soda = unmatchedItem("REFRIG COCA 2L");
        when(receiptItemRepository.findUnmatchedByReceiptId(receiptId)).thenReturn(List.of(rice, mystery, soda));
        // Item 2 comes back as a broken object; the array is also cut off after item 3.
        when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt(), any())).thenReturn("""
                ```json
                [{"n":1,"genericName":"Arroz Branco","brand":null,"category":"GROCERIES","confidence":0.9},
                 {"n":2,"genericName":"Coisa" "brand"},
                 {"n":3,"genericName":"Refrigerante {cola}","brand":"Coca-Cola","category":"BEVERAGES","confidence":0.9},
                 {"n":4,"genericName":"Trunc
                """);
        when(receiptItemRepository.findReceiptIdsWithUnmatchedItems(any())).thenReturn(List.of(receiptId));

        var tally = service.reprocessUnmatched(10);

        assertThat(rice.getProduct()).isNotNull();
        assertThat(mystery.getProduct()).isNull();
        assertThat(soda.getProduct().getGenericName()).isEqualTo("Refrigerante {cola}");
        assertThat(tally.matched()).isEqualTo(2);
        assertThat(tally.parseFailed()).isEqualTo(1);
    }

    @Test
    void wholeAnswerUnparseable_leavesEveryItemUnmatched() {
        when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn("desculpe, não consegui");

        assertThatCode(() -> service.applyFallback(receiptId, userId)).doesNotThrowAnyException();

        assertThat(item.getProduct()).isNull();
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void receiptAboveTheBatchSize_isSplit_oneLedgerRowPerCall() {
        var manyItems = IntStream.range(0, AiItemFallbackService.MAX_ITEMS_PER_CALL + 1)
                .mapToObj(index -> unmatchedItem("ITEM " + index))
                .toList();
        when(receiptItemRepository.findUnmatchedByReceiptId(receiptId)).thenReturn(manyItems);
        when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt(), any())).thenReturn("[]");

        service.applyFallback(receiptId, userId);

        verify(aiGateway, times(2)).complete(any(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(paidApiGuard, times(2)).recordSuccess(userId, PaidApiService.LLM_ENRICH, null, "ai");
    }

    @Test
    void userCapReached_skipsSilently_noCallNoLedgerNoFailure() {
        doThrow(new PaidApiQuotaExceededException("LLM_ENRICH"))
                .when(paidApiGuard).assertWithinDailyCap(userId, PaidApiService.LLM_ENRICH);

        assertThatCode(() -> service.applyFallback(receiptId, userId)).doesNotThrowAnyException();

        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(paidApiGuard, never()).recordSuccess(any(), any(), any(), any());
        assertThat(item.getProduct()).isNull();
    }

    @Test
    void dailyBudgetSpent_skipsSilently() {
        doThrow(new PaidApiBudgetExceededException())
                .when(paidApiGuard).assertUnderDailyBudget(PaidApiService.LLM_ENRICH);

        assertThatCode(() -> service.applyFallback(receiptId, userId)).doesNotThrowAnyException();

        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void adminReprocess_isUnattributed_noUserOnTheLedger() {
        when(receiptItemRepository.findReceiptIdsWithUnmatchedItems(any())).thenReturn(List.of(receiptId));

        service.reprocessUnmatched(10);

        verify(aiGateway).complete(any(), anyString(), anyString(), anyString(), anyInt(), eq(null));
        verify(paidApiGuard).recordSuccess(null, PaidApiService.LLM_ENRICH, null, "ai");
    }

    @Test
    void aiCallFailure_writesNoLedgerRow() {
        when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenThrow(new AiGateway.AiUnavailableException("fora"));

        assertThatCode(() -> service.applyFallback(receiptId, userId)).doesNotThrowAnyException();

        verify(paidApiGuard, never()).recordSuccess(any(), any(), any(), any());
    }
}
