package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;

public record ProductExtraction(
        String genericName,
        String brand,
        BigDecimal packSize,
        String packUnit,
        ProductCategory category,
        CategorizationSource categorizationSource
) {
    public static final ProductExtraction EMPTY =
            new ProductExtraction(null, null, null, null, null, CategorizationSource.NONE);
}
