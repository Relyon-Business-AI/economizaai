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
    private final UUID petId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        classifier = new AiFineCategoryClassifier(aiGateway, categoryRepository);
        var carnes = Category.builder().id(carnesId).slug("carnes").namePt("Carnes").nameEn("Meat")
                .descriptionPt("Carnes, aves e pescados.").examplesPt("Picanha, frango").build();
        var aves = Category.builder().id(avesId).slug("aves").namePt("Aves").nameEn("Poultry").parent(carnes)
                .descriptionPt("Aves.").examplesPt("Frango, peru, chester").build();
        var suinos = Category.builder().id(suinosId).slug("suinos").namePt("Suínos").nameEn("Pork").parent(carnes)
                .descriptionPt("Carne suína e frios.").examplesPt("Bacon, linguiça, presunto").build();
        // categoria SEM subcategoria — alvo válido (só a categoria)
        var pet = Category.builder().id(petId).slug("pet").namePt("Pet").nameEn("Pet")
                .descriptionPt("Produtos para animais.").examplesPt("Ração, petisco").build();
        lenient().when(categoryRepository.findAllByActiveTrueOrderBySortOrderAsc())
                .thenReturn(List.of(carnes, aves, suinos, pet));
        lenient().when(aiGateway.curatorModel()).thenReturn("sonnet");
    }

    @Test
    void suggest_placesValidLeaf_andPromptCarriesGabaritoAndContext() {
        var productId = UUID.randomUUID();
        var systemCaptor = ArgumentCaptor.forClass(String.class);
        var userCaptor = ArgumentCaptor.forClass(String.class);
        when(aiGateway.complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"), anyString(), anyString(), anyInt()))
                .thenReturn("[{\"id\":\"" + productId + "\",\"slug\":\"aves\",\"confidence\":0.9}]");

        var inputs = List.of(new ClassificationInput(productId, "FRANGO RESF KG", "FRANGO", "Sadia", "Carnes", null));
        var result = classifier.suggest(inputs);

        assertEquals(1, result.size());
        assertEquals(avesId, result.get(0).categoryId());
        assertEquals("aves", result.get(0).slug());
        assertEquals(0.9, result.get(0).confidence());

        // modelo curator; gabarito (definição + exemplos) vai no SYSTEM (cacheável); contexto do produto no USER
        verify(aiGateway).complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"),
                systemCaptor.capture(), userCaptor.capture(), anyInt());
        var system = systemCaptor.getValue();
        var user = userCaptor.getValue();
        assertTrue(system.contains("aves (Aves)"), "lista de subcategorias no system");
        assertTrue(system.contains("Frango, peru, chester"), "exemplos do gabarito no system");
        assertTrue(user.contains("nota: \"FRANGO RESF KG\""), "nome da nota no contexto (user)");
        assertTrue(user.contains("categoria atual: Carnes"), "categoria atual no contexto (user)");
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
    void suggest_acceptsCategorySlug_whenOnlyConfidentOnCategory() {
        // a LLM pode devolver só a CATEGORIA quando não tem certeza da subcategoria
        var productId = UUID.randomUUID();
        when(aiGateway.complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"), anyString(), anyString(), anyInt()))
                .thenReturn("[{\"id\":\"" + productId + "\",\"slug\":\"carnes\",\"confidence\":0.7}]");

        var result = classifier.suggest(List.of(
                new ClassificationInput(productId, null, "CARNE XPTO", null, null, null)));

        assertEquals(1, result.size());
        assertEquals(carnesId, result.get(0).categoryId());
        assertEquals("carnes", result.get(0).slug());
    }

    @Test
    void suggest_acceptsLeaflessCategory() {
        // categoria sem subcategoria (Pet): o único alvo possível é o slug da categoria
        var productId = UUID.randomUUID();
        var systemCaptor = ArgumentCaptor.forClass(String.class);
        when(aiGateway.complete(eq(AiActivity.FINE_CATEGORY_CLASSIFY), eq("sonnet"), systemCaptor.capture(), anyString(), anyInt()))
                .thenReturn("[{\"id\":\"" + productId + "\",\"slug\":\"pet\",\"confidence\":0.95}]");

        var result = classifier.suggest(List.of(
                new ClassificationInput(productId, "SACHE CAO BALANCE", "SACHE CAO BALANCE", null, null, null)));

        assertEquals(1, result.size());
        assertEquals(petId, result.get(0).categoryId());
        assertTrue(systemCaptor.getValue().contains("CATEGORIA pet (Pet)"), "categoria sem folha no gabarito");
        assertTrue(systemCaptor.getValue().contains("[sem subcategorias]"), "marca de sem subcategoria");
    }
}
