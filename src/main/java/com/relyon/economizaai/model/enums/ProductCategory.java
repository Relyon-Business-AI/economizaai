package com.relyon.economizaai.model.enums;

/**
 * LEGADO — NÃO use em código novo. A taxonomia de 2 níveis (tabela {@code categories} +
 * {@code products.category_id}) é a fonte da verdade desde a Fase A do refactor (V113):
 * este enum é uma PROJEÇÃO derivada do nó ({@code ProductCategoryNodeListener} node-first),
 * mantida só por compatibilidade (filtros/DTOs legados da API + apps móveis já lançados).
 * Código novo: use o nó/slug ({@code CategoryResolver}, params {@code categorySlug}).
 * Remoção completa: ver {@code docs/ENUM_RETIREMENT.md} (Fase D — gated na migração do FE/mobile).
 */
public enum ProductCategory {
    GROCERIES,
    BEVERAGES,
    PRODUCE,
    MEAT_DAIRY,
    BAKERY,
    CLEANING,
    PERSONAL_CARE,
    HEALTH,
    PET_SUPPLIES,
    OTHER
}
