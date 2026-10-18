package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.ClassificationInput;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.FineSuggestion;
import com.relyon.economizaai.service.extraction.ProductExtraction;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptAiCategorySuggestionServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductExtractor productExtractor;
    @Mock private AiFineCategoryClassifier classifier;
    @Mock private CategoryResolver categoryResolver;

    @InjectMocks private ReceiptAiCategorySuggestionService service;

    private final UUID receiptId = UUID.randomUUID();

    private static final ProductExtraction NO_SIGNAL =
            new ProductExtraction(null, null, null, null, null, null, CategorizationSource.NONE);

    @BeforeEach
    void setUp() {
        lenient().when(classifier.isEnabled()).thenReturn(true);
        lenient().when(receiptRepository.findById(receiptId))
                .thenReturn(Optional.of(receipt(ReceiptStatus.PENDING_CONFIRMATION)));
    }

    private Receipt receipt(ReceiptStatus status) {
        return Receipt.builder().id(receiptId).status(status).build();
    }

    private ReceiptItem item(String description, String ean) {
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription(description)
                .ean(ean)
                .quantity(BigDecimal.ONE)
                .totalPrice(new BigDecimal("9.99"))
                .build();
    }

    @Test
    void disabled_doesNothing() {
        when(classifier.isEnabled()).thenReturn(false);

        service.suggestForReceipt(receiptId);

        verify(receiptRepository, never()).findById(any());
        verify(classifier, never()).suggest(anyList());
    }

    @Test
    void notPendingConfirmation_doesNothing() {
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt(ReceiptStatus.CONFIRMED)));

        service.suggestForReceipt(receiptId);

        verify(classifier, never()).suggest(anyList());
    }

    @Test
    void skipsResolvedExcludedAndKnownEanItems_classifiesOnlyTheUnresolved() {
        var resolvedLeaf = UUID.randomUUID();
        var resolved = item("ARROZ TIO JOAO 5KG", null);          // keyword/dicionário já deram folha
        var excluded = item("SACOLA PLASTICA", null);
        excluded.setExcluded(true);
        var knownEan = item("COCA COLA 2L", "789");               // produto já existe → confirm LINKA
        var unresolved = item("TORRONE FLORESTA 50G", null);

        when(receiptItemRepository.findByReceiptId(receiptId))
                .thenReturn(List.of(resolved, excluded, knownEan, unresolved));
        when(productRepository.findByEan("789")).thenReturn(Optional.of(Product.builder().id(UUID.randomUUID()).build()));
        when(productExtractor.extract(resolved.getRawDescription())).thenReturn(
                new ProductExtraction(null, null, null, null, ProductCategory.GROCERIES, resolvedLeaf, CategorizationSource.DICTIONARY));
        when(categoryResolver.isLeafNode(resolvedLeaf)).thenReturn(true);
        when(productExtractor.extract(unresolved.getRawDescription())).thenReturn(NO_SIGNAL);
        when(classifier.suggest(anyList())).thenReturn(List.of());

        service.suggestForReceipt(receiptId);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(classifier).suggest(captor.capture());
        @SuppressWarnings("unchecked")
        var inputs = (List<ClassificationInput>) captor.getValue();
        assertEquals(1, inputs.size());
        assertEquals(unresolved.getId(), inputs.get(0).productId());
        assertEquals("TORRONE FLORESTA 50G", inputs.get(0).receiptName());
    }

    @Test
    void appliesSuggestionAboveThreshold_persistingAiCategoryId() {
        var leaf = UUID.randomUUID();
        var unresolved = item("TORRONE FLORESTA 50G", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(unresolved));
        when(productExtractor.extract(any())).thenReturn(NO_SIGNAL);
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(unresolved.getId(), leaf, "doces", 0.86)));

        service.suggestForReceipt(receiptId);

        assertEquals(leaf, unresolved.getAiCategoryId());
        var captor = ArgumentCaptor.forClass(List.class);
        verify(receiptItemRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    void appliesCategoryOnlySuggestion_whenAiReturnsGroup() {
        // a IA devolveu só a CATEGORIA (grupo, ex.: Pet) pra um item que o determinístico não pegou
        var petGroup = UUID.randomUUID();
        var sache = item("SACHE CAO BALANCE AD", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(sache));
        when(productExtractor.extract(any())).thenReturn(NO_SIGNAL);
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(sache.getId(), petGroup, "pet", 0.95)));

        service.suggestForReceipt(receiptId);

        assertEquals(petGroup, sache.getAiCategoryId());
    }

    @Test
    void skipsSuggestionBelowThreshold() {
        var unresolved = item("PRODUTO XPTO", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(unresolved));
        when(productExtractor.extract(any())).thenReturn(NO_SIGNAL);
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(unresolved.getId(), UUID.randomUUID(), "doces", 0.40)));

        service.suggestForReceipt(receiptId);

        assertNull(unresolved.getAiCategoryId());
        var captor = ArgumentCaptor.forClass(List.class);
        verify(receiptItemRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void skipsCrossCategorySuggestion_whenDictionaryGaveTheCategory() {
        var merceariaGroup = UUID.randomUUID();
        var frutasLeaf = UUID.randomUUID();
        var itemWithCoarse = item("AZEITE EXTRA VIRGEM", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(itemWithCoarse));
        when(productExtractor.extract(any())).thenReturn(
                new ProductExtraction("Azeite", null, null, null, ProductCategory.GROCERIES, null, CategorizationSource.DICTIONARY));
        when(categoryResolver.idForLegacyEnum(ProductCategory.GROCERIES)).thenReturn(merceariaGroup);
        when(categoryResolver.labelFor(eq(merceariaGroup), any(Locale.class))).thenReturn("Mercearia");
        when(categoryResolver.groupIdOf(frutasLeaf)).thenReturn(UUID.randomUUID()); // outra categoria
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(itemWithCoarse.getId(), frutasLeaf, "frutas", 0.95)));

        service.suggestForReceipt(receiptId);

        assertNull(itemWithCoarse.getAiCategoryId());
    }

    @Test
    void passesDictionaryCategoryAsContext() {
        var merceariaGroup = UUID.randomUUID();
        var itemWithCoarse = item("AZEITE EXTRA VIRGEM", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(itemWithCoarse));
        when(productExtractor.extract(any())).thenReturn(
                new ProductExtraction("Azeite", "Gallo", null, null, ProductCategory.GROCERIES, null, CategorizationSource.DICTIONARY));
        when(categoryResolver.idForLegacyEnum(ProductCategory.GROCERIES)).thenReturn(merceariaGroup);
        when(categoryResolver.labelFor(eq(merceariaGroup), any(Locale.class))).thenReturn("Mercearia");
        when(classifier.suggest(anyList())).thenReturn(List.of());

        service.suggestForReceipt(receiptId);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(classifier).suggest(captor.capture());
        @SuppressWarnings("unchecked")
        var inputs = (List<ClassificationInput>) captor.getValue();
        assertEquals("Mercearia", inputs.get(0).currentCategory());
        assertEquals("Gallo", inputs.get(0).brand());
    }

    @Test
    void aiUnavailable_isGraceful_nothingSaved() {
        var unresolved = item("PRODUTO XPTO", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(unresolved));
        when(productExtractor.extract(any())).thenReturn(NO_SIGNAL);
        when(classifier.suggest(anyList())).thenThrow(new AiGateway.AiUnavailableException("cap"));

        service.suggestForReceipt(receiptId);

        verify(receiptItemRepository, never()).saveAll(anyList());
    }

    @Test
    void confirmedWhileClassifying_skipsApply() {
        var unresolved = item("PRODUTO XPTO", null);
        when(receiptRepository.findById(receiptId))
                .thenReturn(Optional.of(receipt(ReceiptStatus.PENDING_CONFIRMATION)))
                .thenReturn(Optional.of(receipt(ReceiptStatus.CONFIRMED)));
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(unresolved));
        when(productExtractor.extract(any())).thenReturn(NO_SIGNAL);
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(unresolved.getId(), UUID.randomUUID(), "doces", 0.9)));

        service.suggestForReceipt(receiptId);

        verify(receiptItemRepository, never()).saveAll(anyList());
    }

    @Test
    void noCandidates_skipsTheAiCallEntirely() {
        var resolvedLeaf = UUID.randomUUID();
        var resolved = item("ARROZ TIO JOAO 5KG", null);
        when(receiptItemRepository.findByReceiptId(receiptId)).thenReturn(List.of(resolved));
        when(productExtractor.extract(any())).thenReturn(
                new ProductExtraction(null, null, null, null, ProductCategory.GROCERIES, resolvedLeaf, CategorizationSource.DICTIONARY));
        when(categoryResolver.isLeafNode(resolvedLeaf)).thenReturn(true);

        service.suggestForReceipt(receiptId);

        verify(classifier, never()).suggest(anyList());
    }
}
