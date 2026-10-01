package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiFinding;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiItemFallbackServiceTest {

    private static final String CLASSIFICATION_JSON =
            "{\"genericName\":\"Arroz Branco\",\"brand\":\"Camil\",\"category\":\"GROCERIES\",\"confidence\":0.9}";

    @Mock private AiGateway aiGateway;
    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private AiFindingRepository findingRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private AiItemFallbackService service;

    private final UUID receiptId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private ReceiptItem item;

    @BeforeEach
    void setUp() {
        service = new AiItemFallbackService(aiGateway, productRepository, receiptItemRepository,
                findingRepository, transactionTemplate);
        item = ReceiptItem.builder().id(itemId).rawDescription("ARROZ CAMIL 5KG").build();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().when(aiGateway.isEnabled()).thenReturn(true);
        lenient().when(aiGateway.extractorModel()).thenReturn("model");
        lenient().when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(CLASSIFICATION_JSON);
        lenient().when(receiptItemRepository.findUnmatchedByReceiptId(receiptId)).thenReturn(List.of(item));
        lenient().when(receiptItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    }

    @Test
    void classificationSurvivesFindingSaveFailure() {
        when(findingRepository.existsByTypeAndStatusAndTitleStartingWith(
                eq(AiFindingType.MISSING_RULE), eq(AiFindingStatus.PENDING), anyString())).thenReturn(false);
        when(findingRepository.save(any(AiFinding.class))).thenThrow(new RuntimeException("constraint violation"));

        assertThatCode(() -> service.applyFallback(receiptId)).doesNotThrowAnyException();

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

        service.applyFallback(receiptId);

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

        service.applyFallback(receiptId);

        verify(findingRepository, never()).save(any(AiFinding.class));
        assertThat(item.getProduct()).isNotNull();
    }

    @Test
    void skipsFindingWhenItemAlreadyMatched() {
        item.setProduct(Product.builder().id(UUID.randomUUID()).build());

        service.applyFallback(receiptId);

        // Re-checks BEFORE the LLM call — must not even pay for the classification.
        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt());
        verify(productRepository, never()).save(any(Product.class));
        verify(findingRepository, never()).save(any(AiFinding.class));
    }
}
