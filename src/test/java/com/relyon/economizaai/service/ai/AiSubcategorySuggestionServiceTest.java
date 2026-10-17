package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.FineSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSubcategorySuggestionServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private AiFindingRepository findingRepository;
    @Mock private AiFineCategoryClassifier classifier;
    @Mock private CategoryResolver categoryResolver;
    @Mock private ReceiptItemRepository receiptItemRepository;

    private AiSubcategorySuggestionService service;

    @BeforeEach
    void setUp() {
        service = new AiSubcategorySuggestionService(
                productRepository, findingRepository, classifier, categoryResolver, receiptItemRepository);
        lenient().when(classifier.isEnabled()).thenReturn(true);
        lenient().when(categoryResolver.groupIds()).thenReturn(Set.of(UUID.randomUUID()));
    }

    @Test
    void generate_throwsWhenAiDisabled() {
        when(classifier.isEnabled()).thenReturn(false);
        assertThrows(AiGateway.AiUnavailableException.class, () -> service.generate(50));
    }

    @Test
    void generate_createsOneFindingPerSuggestion_withPriceAndMarket() {
        var queijosNode = UUID.randomUUID();
        // Nome sem keyword (NONE) → cai no LLM (o caminho que este teste exercita).
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("ITEM XPTO 500").category(ProductCategory.MEAT_DAIRY).build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any()))
                .thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY))
                .thenReturn(List.of());
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(product.getId(), queijosNode, "queijos", 0.95)));
        when(categoryResolver.labelFor(eq(queijosNode), any(Locale.class))).thenReturn("Queijos");
        when(categoryResolver.labelFor(eq(null), any(Locale.class))).thenReturn(null);
        when(categoryResolver.legacyEnumForNode(queijosNode)).thenReturn(ProductCategory.MEAT_DAIRY);
        when(receiptItemRepository.findLastPriceAndMarket(eq(product.getId()), any()))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("13.90"), "ATACADO NILO"}));

        var outcome = service.generate(50);

        assertEquals(1, outcome.created());
        // saveAll é chamado 2x (lote keyword vazio + lote LLM) — pega o não-vazio.
        var captor = ArgumentCaptor.forClass(List.class);
        verify(findingRepository, times(2)).saveAll(captor.capture());
        var saved = captor.getAllValues().stream().filter(list -> !list.isEmpty()).findFirst().orElseThrow();
        var finding = (AiFinding) saved.get(0);
        assertEquals(AiFindingType.SUSPECT_CATEGORY, finding.getType());
        assertEquals(AiFindingStatus.PENDING, finding.getStatus());
        assertEquals(new BigDecimal("0.950"), finding.getConfidence());
        assertTrue(finding.getPayload().contains("\"categoryNodeSlug\":\"queijos\""));
        assertTrue(finding.getPayload().contains("\"category\":\"MEAT_DAIRY\""));
        assertTrue(finding.getPayload().contains("\"suggestedLabel\":\"Queijos\""));
        assertTrue(finding.getPayload().contains("\"lastPrice\":13.90"));
        assertTrue(finding.getPayload().contains("\"lastMarket\":\"ATACADO NILO\""));
    }

    @Test
    void generate_keywordHigh_createsFindingWithoutLlm() {
        var utilidadesNode = UUID.randomUUID();
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("PILHA DURACELL AA C/4").build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any()))
                .thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY))
                .thenReturn(List.of());
        when(categoryResolver.idForSlug("utilidades")).thenReturn(utilidadesNode);
        when(categoryResolver.labelFor(eq(utilidadesNode), any(Locale.class))).thenReturn("Utilidades");
        lenient().when(categoryResolver.labelFor(eq(null), any(Locale.class))).thenReturn(null);
        lenient().when(categoryResolver.legacyEnumForNode(utilidadesNode)).thenReturn(null);
        when(receiptItemRepository.findLastPriceAndMarket(eq(product.getId()), any())).thenReturn(List.of());

        var outcome = service.generate(50);

        assertEquals(1, outcome.created());
        verify(classifier, never()).suggest(anyList()); // keyword resolveu — não chamou o LLM
    }

    @Test
    void generate_discardsCrossCategorySuggestion() {
        // produto em Mercearia; LLM sugere folha de Hortifrúti com confiança BAIXA (0.7) → mudar de
        // categoria exige >= change-category-min (0.9), então é descartado (gating assimétrico, option B)
        var merceariaGroup = UUID.randomUUID();
        var hortifrutiGroup = UUID.randomUUID();
        var hortifrutiLeaf = UUID.randomUUID();
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("PRODUTO XPTO 500").categoryId(merceariaGroup).build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any())).thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY)).thenReturn(List.of());
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(product.getId(), hortifrutiLeaf, "frutas", 0.7)));
        when(categoryResolver.groupIdOf(merceariaGroup)).thenReturn(merceariaGroup);
        when(categoryResolver.groupIdOf(hortifrutiLeaf)).thenReturn(hortifrutiGroup);

        var outcome = service.generate(50);

        assertEquals(0, outcome.created());
    }

    @Test
    void generate_allowsCrossCategoryChangeWhenConfidenceHigh() {
        // mesma mudança de categoria do teste acima, mas confiança ALTA (0.95 >= 0.9) → agora PASSA,
        // marcada como CHANGE_CATEGORY pro admin ver que é uma troca de categoria (option B)
        var merceariaGroup = UUID.randomUUID();
        var hortifrutiGroup = UUID.randomUUID();
        var hortifrutiLeaf = UUID.randomUUID();
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("PRODUTO XPTO 500").category(ProductCategory.GROCERIES).categoryId(merceariaGroup).build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any())).thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY)).thenReturn(List.of());
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(product.getId(), hortifrutiLeaf, "frutas", 0.95)));
        when(categoryResolver.groupIdOf(merceariaGroup)).thenReturn(merceariaGroup);
        when(categoryResolver.groupIdOf(hortifrutiLeaf)).thenReturn(hortifrutiGroup);
        when(categoryResolver.isLeafNode(merceariaGroup)).thenReturn(false);
        when(categoryResolver.labelFor(eq(merceariaGroup), any(Locale.class))).thenReturn("Mercearia");
        when(categoryResolver.labelFor(eq(hortifrutiLeaf), any(Locale.class))).thenReturn("Frutas");
        when(categoryResolver.labelFor(eq(hortifrutiGroup), any(Locale.class))).thenReturn("Hortifrúti");
        when(receiptItemRepository.findLastPriceAndMarket(eq(product.getId()), any())).thenReturn(List.of());

        var outcome = service.generate(50);

        assertEquals(1, outcome.created());
        var captor = ArgumentCaptor.forClass(List.class);
        verify(findingRepository, times(2)).saveAll(captor.capture());
        var saved = captor.getAllValues().stream().filter(list -> !list.isEmpty()).findFirst().orElseThrow();
        var finding = (AiFinding) saved.get(0);
        assertTrue(finding.getPayload().contains("\"changesCategory\":true"));
        assertTrue(finding.getPayload().contains("\"action\":\"CHANGE_CATEGORY\""));
    }

    @Test
    void generate_skipsChangeSubcategoryBelowThreshold() {
        // produto já tem subcategoria (Aves); LLM sugere outra do MESMO grupo (Suínos) com 0.70 →
        // trocar subcategoria exige >= change-subcategory-min (0.8) → descartado
        var carnesGroup = UUID.randomUUID();
        var avesNode = UUID.randomUUID();
        var suinosNode = UUID.randomUUID();
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("PRODUTO XPTO 500").category(ProductCategory.MEAT_DAIRY).categoryId(avesNode).build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any())).thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY)).thenReturn(List.of());
        when(classifier.suggest(anyList()))
                .thenReturn(List.of(new FineSuggestion(product.getId(), suinosNode, "suinos", 0.70)));
        when(categoryResolver.groupIdOf(avesNode)).thenReturn(carnesGroup);
        when(categoryResolver.groupIdOf(suinosNode)).thenReturn(carnesGroup);
        when(categoryResolver.isLeafNode(avesNode)).thenReturn(true);
        lenient().when(categoryResolver.labelFor(eq(avesNode), any(Locale.class))).thenReturn("Aves");

        var outcome = service.generate(50);

        assertEquals(0, outcome.created());
    }

    @Test
    void generate_skipsProductsAlreadyHavingAPendingSuggestion() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        var existing = AiFinding.builder().type(AiFindingType.SUSPECT_CATEGORY)
                .payload("{\"productId\":\"" + product.getId() + "\"}").build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any()))
                .thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY))
                .thenReturn(List.of(existing));

        var outcome = service.generate(50);

        assertEquals(0, outcome.created());
        assertEquals(1, outcome.skippedExisting());
    }

    @Test
    void generate_stopsGracefullyWhenAiCapRunsOut() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        when(productRepository.findWithoutSubcategory(any(), any(), any(), any()))
                .thenReturn(List.of(product));
        when(findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY))
                .thenReturn(List.of());
        when(classifier.suggest(anyList())).thenThrow(new AiGateway.AiUnavailableException("sem credito"));

        var outcome = service.generate(50);

        assertTrue(outcome.stopped());
        assertEquals(0, outcome.created());
    }
}
