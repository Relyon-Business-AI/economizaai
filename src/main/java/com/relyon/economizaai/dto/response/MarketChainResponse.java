package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MarketChain;

import java.util.UUID;

public record MarketChainResponse(
        UUID id,
        String cnpjRoot,
        String name,
        String logoUrl
) {
    public static MarketChainResponse from(MarketChain chain) {
        String logoUrl = chain.getLogoUrl() != null
                ? "/chains/logo/" + chain.getId()
                : null;
        return new MarketChainResponse(chain.getId(), chain.getCnpjRoot(), chain.getName(), logoUrl);
    }
}
