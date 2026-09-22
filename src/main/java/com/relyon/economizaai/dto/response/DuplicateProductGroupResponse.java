package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.util.List;

/**
 * A group of products that share the same `(genericName, brand, packSize,
 * packUnit)` profile and are therefore likely duplicates. The admin merge
 * tool consumes this to let a curator pick a survivor and merge the rest.
 */
public record DuplicateProductGroupResponse(
        String genericName,
        String brand,
        BigDecimal packSize,
        String packUnit,
        ProductCategory category,
        List<ProductResponse> products
) {}
