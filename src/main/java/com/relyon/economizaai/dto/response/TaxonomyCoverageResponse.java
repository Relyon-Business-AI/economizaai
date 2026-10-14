package com.relyon.economizaai.dto.response;

/**
 * Catálogo: quantos produtos já estão mapeados numa folha fina da taxonomia
 * (category_id) vs ainda sem nó (caem no default do grupo do enum). Métrica de
 * cobertura da migração pro modelo novo, exibida no admin de Categorização.
 */
public record TaxonomyCoverageResponse(long total, long withLeaf, long withoutLeaf) {
    public static TaxonomyCoverageResponse of(long total, long withLeaf) {
        return new TaxonomyCoverageResponse(total, withLeaf, total - withLeaf);
    }
}
