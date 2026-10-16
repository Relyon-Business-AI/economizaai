package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryResolverTest {

    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private CategoryResolver resolver;

    private Category bebidas;
    private Category refrigerantes;
    private Category limpeza;

    private void warmSeed() {
        bebidas = Category.builder().id(UUID.randomUUID()).slug("bebidas").legacyEnum(ProductCategory.BEVERAGES).build();
        refrigerantes = Category.builder().id(UUID.randomUUID()).slug("refrigerantes").parent(bebidas).build();
        limpeza = Category.builder().id(UUID.randomUUID()).slug("limpeza").legacyEnum(ProductCategory.CLEANING).build();
        when(categoryRepository.findAll()).thenReturn(List.of(bebidas, refrigerantes, limpeza));
        resolver.warmUp();
    }

    @Test
    void reconcileNode_nullEnumClearsNode() {
        warmSeed();
        assertNull(resolver.reconcileNode(null, refrigerantes.getId()), "null enum → null node");
    }

    @Test
    void reconcileNode_noNodeFallsBackToGroup() {
        warmSeed();
        assertEquals(bebidas.getId(), resolver.reconcileNode(ProductCategory.BEVERAGES, null),
                "no node → the enum's group");
    }

    @Test
    void reconcileNode_keepsLeafUnderRightGroup() {
        warmSeed();
        assertEquals(refrigerantes.getId(), resolver.reconcileNode(ProductCategory.BEVERAGES, refrigerantes.getId()),
                "a leaf under the enum's group is preserved");
    }

    @Test
    void reconcileNode_collapsesStaleLeafFromAnotherGroup() {
        warmSeed();
        // product had the BEVERAGES/refrigerantes leaf, enum now says CLEANING → must drop to limpeza group
        assertEquals(limpeza.getId(), resolver.reconcileNode(ProductCategory.CLEANING, refrigerantes.getId()),
                "a leaf from another group collapses to the new enum's group");
    }

    @Test
    void reconcileNode_keepsNodeUnderEnumlessGroup() {
        // laticínios e ovos é um grupo SEM legacy_enum; seu nó "ovos" deve ser mantido mesmo
        // com o produto carregando o enum MEAT_DAIRY (senão a subcategoria nunca grudaria).
        var laticinios = Category.builder().id(UUID.randomUUID()).slug("laticinios-ovos").legacyEnum(null).build();
        var ovos = Category.builder().id(UUID.randomUUID()).slug("ovos").parent(laticinios).build();
        var carnes = Category.builder().id(UUID.randomUUID()).slug("carnes").legacyEnum(ProductCategory.MEAT_DAIRY).build();
        when(categoryRepository.findAll()).thenReturn(List.of(laticinios, ovos, carnes));
        resolver.warmUp();

        assertEquals(ovos.getId(), resolver.reconcileNode(ProductCategory.MEAT_DAIRY, ovos.getId()),
                "nó de grupo sem enum é preservado, não colapsa pro grupo do enum");
        assertNull(resolver.legacyEnumForNode(ovos.getId()), "folha de grupo sem enum → enum nulo");
    }

    @Test
    void groupIdOf_andIsLeafNode() {
        warmSeed();
        assertEquals(bebidas.getId(), resolver.groupIdOf(bebidas.getId()), "a group is its own group");
        assertEquals(bebidas.getId(), resolver.groupIdOf(refrigerantes.getId()), "a leaf resolves to its parent group");
        assertTrue(resolver.isLeafNode(refrigerantes.getId()));
        assertFalse(resolver.isLeafNode(bebidas.getId()));
        assertFalse(resolver.isLeafNode(null));
    }

    @Test
    void nodeAndDescendantIds_groupReturnsItselfPlusLeaves() {
        var carnes = Category.builder().id(UUID.randomUUID()).slug("carnes").legacyEnum(ProductCategory.MEAT_DAIRY).build();
        var aves = Category.builder().id(UUID.randomUUID()).slug("aves").parent(carnes).build();
        var bovinos = Category.builder().id(UUID.randomUUID()).slug("carnes-bovinas").parent(carnes).build();
        var leite = Category.builder().id(UUID.randomUUID()).slug("leite")
                .parent(Category.builder().id(UUID.randomUUID()).slug("laticinios-ovos").build()).build();
        when(categoryRepository.findAll()).thenReturn(List.of(carnes, aves, bovinos, leite));
        resolver.warmUp();

        assertEquals(Set.of(carnes.getId(), aves.getId(), bovinos.getId()),
                resolver.nodeAndDescendantIds("carnes"), "group → itself + its leaves");
        assertEquals(Set.of(aves.getId()), resolver.nodeAndDescendantIds("aves"), "leaf → just itself");
        assertEquals(Set.of(), resolver.nodeAndDescendantIds("inexistente"));
        assertEquals(carnes.getId(), resolver.idForLegacyEnum(ProductCategory.MEAT_DAIRY));
    }
}
