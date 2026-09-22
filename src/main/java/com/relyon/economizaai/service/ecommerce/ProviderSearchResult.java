package com.relyon.economizaai.service.ecommerce;

import java.util.List;

/**
 * One page of a marketplace term search. {@code total} is the marketplace-reported
 * total match count (for paging), independent of this page's size.
 */
public record ProviderSearchResult(int total, List<ProviderProduct> products) {

    public static ProviderSearchResult empty() {
        return new ProviderSearchResult(0, List.of());
    }
}
