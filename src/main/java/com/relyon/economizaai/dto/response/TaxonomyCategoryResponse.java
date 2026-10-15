package com.relyon.economizaai.dto.response;

/**
 * One node of the two-level category taxonomy, for the FE to render chips/filters
 * and the correction picker. {@code parentSlug} is null for a top-level category;
 * {@code hasSubcategories} is true when the category has subcategories (acts as a heading).
 */
public record TaxonomyCategoryResponse(String slug, String name, String parentSlug, boolean hasSubcategories) {}
