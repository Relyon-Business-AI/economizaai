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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryResolverTest {

    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private CategoryResolver resolver;

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
