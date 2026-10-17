package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.ClassificationInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiFineCategoryClassifierTest {

    @Mock private AiGateway aiGateway;
    @Mock private CategoryRepository categoryRepository;

    private AiFineCategoryClassifier classifier;

    private final UUID carnesId = UUID.randomUUID();
    private final UUID avesId = UUID.randomUUID();
    private final UUID suinosId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        classifier = new AiFineCategoryClassifier(aiGateway, categoryRepository);
        var carnes = Category.builder().id(carnesId).slug("carnes").namePt("Carnes").nameEn("Meat")
                .descriptionPt("Carnes, aves e pescados.").examplesPt("Picanha, frango").build();
        var aves = Category.builder().id(avesId).slug("aves").namePt("Aves").nameEn("Poultry").parent(carnes)
                .descriptionPt("Aves.").examplesPt("Frango, peru, chester").build();
        var suinos = Category.builder().id(suinosId).slug("suinos").namePt("Suínos").nameEn("Pork").parent(carnes)
                .descriptionPt("Carne suína e frios.").examplesPt("Bacon, linguiça, presunto").build();
        lenient().when(categoryRepository.findAllByActiveTrueOrderBySortOrderAsc())
                .thenReturn(List.of(carnes, aves, suinos));
        lenient().when(aiGateway.curatorModel()).thenReturn("sonnet");
    }

    @Test
    void suggest_placesValidLeaf_andPromptCarriesGabaritoAndContext() {
        var productId = UUID.randomUUID();
        var promptCaptor = ArgumentCaptor.forClass(String.class);
        when(aiGateway.complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"), anyString(), anyString(), anyInt()))
                .thenReturn("[{\"id\":\"" + productId + "\",\"slug\":\"aves\",\"confidence\":0.9}]");

        var inputs = List.of(new ClassificationInput(productId, "FRANGO RESF KG", "FRANGO", "Sadia", "Carnes", null));
        var result = classifier.suggest(inputs);

        assertEquals(1, result.size());
        assertEquals(avesId, result.get(0).categoryId());
        assertEquals("aves", result.get(0).slug());
        assertEquals(0.9, result.get(0).confidence());

        // curator model usado; prompt carrega o gabarito (definição + exemplos) e o contexto do produto
        verify(aiGateway).complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"),
                anyString(), promptCaptor.capture(), anyInt());
        var prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("aves (Aves)"), "lista de subcategorias");
        assertTrue(prompt.contains("Frango, peru, chester"), "exemplos do gabarito");
        assertTrue(prompt.contains("nota: \"FRANGO RESF KG\""), "nome da nota no contexto");
        assertTrue(prompt.contains("categoria atual: Carnes"), "categoria atual no contexto");
    }

    @Test
    void suggest_skipsInvalidSlug() {
        var productId = UUID.randomUUID();
        when(aiGateway.complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"), anyString(), anyString(), anyInt()))
                .thenReturn("[{\"id\":\"" + productId + "\",\"slug\":\"inventado\",\"confidence\":0.9}]");

        var result = classifier.suggest(List.of(
                new ClassificationInput(productId, null, "XPTO", null, "Carnes", null)));

        assertTrue(result.isEmpty());
    }

    @Test
    void suggest_skipsCategorySlug_onlyLeavesAreValid() {
        var productId = UUID.randomUUID();
        // "carnes" é CATEGORIA (não folha) → não é alvo válido; o classificador pula
        when(aiGateway.complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"), anyString(), anyString(), anyInt()))
                .thenReturn("[{\"id\":\"" + productId + "\",\"slug\":\"carnes\",\"confidence\":0.9}]");

        var result = classifier.suggest(List.of(
                new ClassificationInput(productId, null, "XPTO", null, "Carnes", null)));

        assertTrue(result.isEmpty());
    }
}
