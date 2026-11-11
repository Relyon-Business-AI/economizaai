package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.ItemCorrectionFailedException;
import com.relyon.economizaai.exception.ItemCorrectionUnavailableException;
import com.relyon.economizaai.exception.ReceiptItemNotFoundException;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.HouseholdProductCategoryOverrideService;
import com.relyon.economizaai.service.ai.AiGateway.AiUnavailableException;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiItemCorrectionServiceTest {

    private static final String AI_ANSWER =
            "{\"genericName\":\"Queijo Minas\",\"brand\":\"Tirolez\",\"category\":\"MEAT_DAIRY\",\"confidence\":0.9}";

    @Mock private AiGateway aiGateway;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private AiFindingRepository findingRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;

    private AiItemCorrectionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Household household;
    private Receipt receipt;
    private Product sharedProduct;
    private ReceiptItem item;

    @BeforeEach
    void setUp() {
        service = new AiItemCorrectionService(aiGateway, receiptItemRepository, findingRepository,
                transactionTemplate, canonicalizationService, householdProductAliasService, categoryOverrideService);
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        receipt = Receipt.builder().id(UUID.randomUUID()).household(household).build();
        sharedProduct = Product.builder().id(UUID.randomUUID())
                .normalizedName("QJ MIN FRESCAL KG").genericName("Requeijão").brand("Outra")
                .category(ProductCategory.GROCERIES).build();
        item = ReceiptItem.builder().id(UUID.randomUUID()).receipt(receipt).product(sharedProduct)
                .rawDescription("QJ MIN FRESCAL KG").quantity(BigDecimal.ONE).totalPrice(new BigDecimal("30.00")).build();

        lenient().when(aiGateway.isEnabled()).thenReturn(true);
        lenient().when(aiGateway.extractorModel()).thenReturn("extractor-model");
        lenient().when(receiptItemRepository.findByIdWithReceiptAndHousehold(item.getId())).thenReturn(Optional.of(item));
        lenient().when(canonicalizationService.linkOrCreateProduct(receipt, item)).thenReturn(sharedProduct);
        lenient().when(findingRepository.findByStatus(AiFindingStatus.PENDING)).thenReturn(List.of());
    }

    private void aiAnswers(String answer) {
        when(aiGateway.complete(eq(AiActivity.ITEM_CORRECTION), any(), any(), any(), anyInt())).thenReturn(answer);
    }

    private void correct() {
        service.correct(receipt.getId(), item.getId(), household.getId(), "é queijo minas da Tirolez");
    }

    @Test
    void correctionIsAppliedToTheHouseholdAndNeverToTheSharedProduct() {
        aiAnswers(AI_ANSWER);

        correct();

        assertEquals("Queijo Minas Tirolez", item.getFriendlyDescription());
        verify(householdProductAliasService).rememberFromItem(household, item);
        verify(categoryOverrideService).setHouseholdOverride(household, sharedProduct, ProductCategory.MEAT_DAIRY);
        // The row every household shares is exactly as it was.
        assertEquals("Requeijão", sharedProduct.getGenericName());
        assertEquals("Outra", sharedProduct.getBrand());
        assertEquals(ProductCategory.GROCERIES, sharedProduct.getCategory());
        assertEquals("QJ MIN FRESCAL KG", sharedProduct.getNormalizedName());
    }

    @Test
    void globalChangeIsQueuedAsAPendingFindingForTheAdmin() throws Exception {
        aiAnswers(AI_ANSWER);

        correct();

        var saved = ArgumentCaptor.forClass(AiFinding.class);
        verify(findingRepository, times(2)).save(saved.capture());
        var productCorrection = saved.getAllValues().stream()
                .filter(finding -> finding.getType() == AiFindingType.PRODUCT_CORRECTION).findFirst().orElseThrow();
        assertEquals(AiFindingStatus.PENDING, productCorrection.getStatus());
        var payload = objectMapper.readTree(productCorrection.getPayload());
        assertEquals(sharedProduct.getId().toString(), payload.path("productId").asText());
        assertEquals("Queijo Minas", payload.path("genericName").asText());
        assertEquals("Tirolez", payload.path("brand").asText());
        assertEquals("MEAT_DAIRY", payload.path("category").asText());
        assertEquals("GROCERIES", payload.path("productCategory").asText());
        assertEquals("é queijo minas da Tirolez", payload.path("userHint").asText());
        assertTrue(saved.getAllValues().stream().anyMatch(finding -> finding.getType() == AiFindingType.MISSING_RULE),
                "the dictionary-rule proposal is still filed");
    }

    @Test
    void secondCorrectionOfTheSameProductDoesNotStackAnotherGlobalProposal() {
        aiAnswers(AI_ANSWER);
        when(findingRepository.existsByTypeAndStatusAndPayloadContaining(
                eq(AiFindingType.PRODUCT_CORRECTION), eq(AiFindingStatus.PENDING), any())).thenReturn(true);

        correct();

        var saved = ArgumentCaptor.forClass(AiFinding.class);
        verify(findingRepository).save(saved.capture());
        assertEquals(AiFindingType.MISSING_RULE, saved.getValue().getType());
        verify(categoryOverrideService).setHouseholdOverride(household, sharedProduct, ProductCategory.MEAT_DAIRY);
    }

    @Test
    void unlinkedItemIsAnchoredByTheDeterministicLinkNotByTheAiText() {
        item.setProduct(null);
        var createdFromDescription = Product.builder().id(UUID.randomUUID()).normalizedName("QJ MIN FRESCAL KG").build();
        when(canonicalizationService.linkOrCreateProduct(receipt, item)).thenReturn(createdFromDescription);
        aiAnswers("{\"genericName\":\"Queijo Minas\",\"brand\":null,\"category\":\"MEAT_DAIRY\"}");

        correct();

        assertEquals("Queijo Minas", item.getFriendlyDescription());
        verify(categoryOverrideService).setHouseholdOverride(household, createdFromDescription, ProductCategory.MEAT_DAIRY);
        assertNull(createdFromDescription.getGenericName(), "the AI/user text must not land on the shared product");
    }

    @Test
    void aiDisabledIsALocalizedUnavailableError() {
        when(aiGateway.isEnabled()).thenReturn(false);

        var thrown = assertThrows(ItemCorrectionUnavailableException.class, this::correct);

        assertEquals("item.correction.unavailable", thrown.getMessageKey());
        verifyNoInteractions(receiptItemRepository);
    }

    @Test
    void aiBudgetOrProviderFailureIsALocalizedUnavailableError() {
        when(aiGateway.complete(any(), any(), any(), any(), anyInt()))
                .thenThrow(new AiUnavailableException("cap diário atingido"));

        assertThrows(ItemCorrectionUnavailableException.class, this::correct);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void itemFromAnotherHouseholdIsNotFound() {
        var thrown = assertThrows(ReceiptItemNotFoundException.class,
                () -> service.correct(receipt.getId(), item.getId(), UUID.randomUUID(), "dica"));

        assertEquals("receipt.item.not.found", thrown.getMessageKey());
        verify(aiGateway, never()).complete(any(), any(), any(), any(), anyInt());
    }

    @Test
    void itemOfAnotherReceiptIsNotFound() {
        assertThrows(ReceiptItemNotFoundException.class,
                () -> service.correct(UUID.randomUUID(), item.getId(), household.getId(), "dica"));
    }

    @Test
    void unparseableAnswerIsALocalizedFailure() {
        aiAnswers("desculpe, não sei");

        var thrown = assertThrows(ItemCorrectionFailedException.class, this::correct);

        assertEquals("item.correction.failed", thrown.getMessageKey());
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void answerWithoutAProductNameIsALocalizedFailure() {
        aiAnswers("{\"genericName\":\"  \",\"brand\":null,\"category\":\"OTHER\"}");

        assertThrows(ItemCorrectionFailedException.class, this::correct);
        verifyNoInteractions(categoryOverrideService, householdProductAliasService);
    }

    @Test
    void unknownCategoryFallsBackToOther() {
        aiAnswers("{\"genericName\":\"Vela\",\"brand\":null,\"category\":\"CANDLES\"}");

        correct();

        verify(categoryOverrideService).setHouseholdOverride(household, sharedProduct, ProductCategory.OTHER);
    }
}
