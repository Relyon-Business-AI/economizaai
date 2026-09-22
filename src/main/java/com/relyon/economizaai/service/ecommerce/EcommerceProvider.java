package com.relyon.economizaai.service.ecommerce;

import java.util.List;

/**
 * Pluggable e-commerce integration. One implementation per e-commerce (Mercado Livre,
 * Amazon, Magalu…). {@link EcommerceOfferService} discovers all of them via Spring's
 * {@code List<EcommerceProvider>} injection and queries only the configured ones — so
 * adding a new e-commerce is: implement this + add its config block. No orchestration
 * code changes.
 *
 * <p>Implementations MUST be inert when {@link #isConfigured()} is false (return an
 * empty list, never throw) so the feature ships dark until credentials land.
 */
public interface EcommerceProvider {

    /** Short stable key, matches the {@code economizaai.ecommerce.providers.<key>} block. */
    String key();

    /** True only when this provider has the credentials it needs to call its API. */
    boolean isConfigured();

    /**
     * Best-effort catalog lookup by barcode. Returns an empty list when not configured,
     * when nothing matches, or on any provider error (never throws) — the caller merges
     * results across providers and with curated offers.
     *
     * @param cep destination CEP for freight, may be null/blank (freight then omitted).
     */
    List<ProviderOffer> searchByEan(String ean, String cep);
}
