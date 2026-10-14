package com.relyon.economizaai.dto.response;

/**
 * One node of the two-level category taxonomy, for the FE to render chips/filters
 * and the correction picker. {@code parentSlug} is null for a top-level group;
 * {@code group} is true when the node has children (acts as a heading).
 */
public record TaxonomyCategoryResponse(String slug, String name, String parentSlug, boolean group) {}
