package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.LlmDisagreementRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.extraction.DictionaryClassifier;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
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
class LlmEnrichmentServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private CuratedDictionaryEntryRepository curatedRepository;
    @Mock private LlmDisagreementRepository disagreementRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private OpenAiClient openAiClient;
    @Mock private PaidApiGuardService paidApiGuard;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LlmEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new LlmEnrichmentService(productRepository, receiptItemRepository, curatedRepository,
                disagreementRepository, dictionaryClassifier, openAiClient, paidApiGuard, transactionTemplate,
                true, 25, 3, 0.7);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Product product(String name, CategorizationSource source, ProductCategory category) {
        var product = Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(name)
                .category(category)
                .categorizationSource(source)
                .build();
        lenient().when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        return product;
    }

    private void stubResponse(String json) throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(objectMapper.readTree(json));
    }

    @Test
    void enrich_appliesCategoryBrandAndWritesDictionaryRule() throws Exception {
        var azeite = product("AZEIT GALLO 500ml UN", CategorizationSource.NONE, ProductCategory.OTHER);
        when(productRepository.findEnrichmentCandidates(anyInt(), any(Pageable.class)))
                .thenReturn(List.of(azeite));
        when(curatedRepository.findByKeyword("azeit")).thenReturn(Optional.empty());
        stubResponse("""
                {"products": [{"id": 0, "category": "GROCERIES", "brand": "Gallo",
                 "generic_name": "Azeite", "pack_size": 500, "pack_unit": "ml",
                 "confidence": 0.95, "dictionary_keyword": "azeit"}]}""");

        service.enrichPendingProducts();

        assertThat(azeite.getCategory()).isEqualTo(ProductCategory.GROCERIES);
        assertThat(azeite.getCategorizationSource()).isEqualTo(CategorizationSource.LLM);
        assertThat(azeite.getBrand()).isEqualTo("Gallo");
        assertThat(azeite.getPackSize()).isEqualByComparingTo("500");
        verify(curatedRepository).save(any());
        verify(dictionaryClassifier).reloadCuratedEntries();
        verify(paidApiGuard).recordSuccess(null, PaidApiService.LLM_ENRICH, null, "openai");
    }

    @Test
    void enrich_lowConfidence_leavesProductUntouched() throws Exception {
        var mystery = product("XPTO 123", CategorizationSource.NONE, ProductCategory.OTHER);
        when(productRepository.findEnrichmentCandidates(anyInt(), any(Pageable.class)))
                .thenReturn(List.of(mystery));
        stubResponse("""
                {"products": [{"id": 0, "category": "GROCERIES", "brand": null,
                 "generic_name": null, "pack_size": null, "pack_unit": null,
                 "confidence": 0.3, "dictionary_keyword": null}]}""");

        service.enrichPendingProducts();

        assertThat(mystery.getCategory()).isEqualTo(ProductCategory.OTHER);
        assertThat(mystery.getCategorizationSource()).isEqualTo(CategorizationSource.NONE);
    }

    @Test
    void enrich_neverOverwritesHigherRankedSource_recordsDisagreement() throws Exception {
        var curated = product("LEITE INTEGRAL", CategorizationSource.DICTIONARY, ProductCategory.MEAT_DAIRY);
        curated.setBrand("Italac");
        curated.setPackSize(BigDecimal.ONE);
        when(productRepository.findEnrichmentCandidates(anyInt(), any(Pageable.class)))
                .thenReturn(List.of(curated));
        when(disagreementRepository.existsByProductIdAndFieldAndResolvedAtIsNull(curated.getId(), "category"))
                .thenReturn(false);
        stubResponse("""
                {"products": [{"id": 0, "category": "BEVERAGES", "brand": null,
                 "generic_name": null, "pack_size": null, "pack_unit": null,
                 "confidence": 0.9, "dictionary_keyword": null}]}""");

        service.enrichPendingProducts();

        assertThat(curated.getCategory()).isEqualTo(ProductCategory.MEAT_DAIRY);
        assertThat(curated.getCategorizationSource()).isEqualTo(CategorizationSource.DICTIONARY);
        verify(disagreementRepository).save(any());
    }

    @Test
    void enrich_rejectsPackSizeFailingPriceSanity() throws Exception {
        var doce = product("DOCE LEITE 395GITA", CategorizationSource.NONE, ProductCategory.OTHER);
        when(productRepository.findEnrichmentCandidates(anyInt(), any(Pageable.class)))
                .thenReturn(List.of(doce));
        // unitPrice R$15.69 with a claimed 3.95g pack → R$3972/kg → absurd → reject
        var item = ReceiptItem.builder().unitPrice(new BigDecimal("15.69")).build();
        when(receiptItemRepository.findFirstByProductIdOrderByCreatedAtDesc(doce.getId()))
                .thenReturn(Optional.of(item));
        when(disagreementRepository.existsByProductIdAndFieldAndResolvedAtIsNull(doce.getId(), "pack"))
                .thenReturn(false);
        stubResponse("""
                {"products": [{"id": 0, "category": "GROCERIES", "brand": "Itambe",
                 "generic_name": "Doce de leite", "pack_size": 3.95, "pack_unit": "g",
                 "confidence": 0.9, "dictionary_keyword": null}]}""");

        service.enrichPendingProducts();

        assertThat(doce.getPackSize()).isNull();
        assertThat(doce.getCategory()).isEqualTo(ProductCategory.GROCERIES);
        verify(disagreementRepository).save(any());
    }

    @Test
    void enrich_disabled_neverCalls() {
        var disabled = new LlmEnrichmentService(productRepository, receiptItemRepository, curatedRepository,
                disagreementRepository, dictionaryClassifier, openAiClient, paidApiGuard, transactionTemplate,
                false, 25, 3, 0.7);

        disabled.enrichPendingProducts();

        verify(productRepository, never()).findEnrichmentCandidates(anyInt(), any(Pageable.class));
    }

    @Test
    void enrich_callFailure_bumpsAttemptsAndRecordsFailure() throws Exception {
        var pending = product("MISTERIO", CategorizationSource.NONE, ProductCategory.OTHER);
        when(productRepository.findEnrichmentCandidates(anyInt(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJson(anyString(), anyString(), anyInt()))
                .thenThrow(new LlmCallFailedException("timeout"));

        service.enrichPendingProducts();

        assertThat(pending.getLlmEnrichmentAttempts()).isEqualTo(1);
        verify(paidApiGuard).recordFailure(null, PaidApiService.LLM_ENRICH, null, "openai");
    }
}
