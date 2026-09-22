package com.relyon.economizaai.dto.response;

import java.util.List;

/** One page of a garimpo term search. {@code total} is the marketplace-reported match count. */
public record GarimpoSearchResponse(
        String query,
        String marketplace,
        int total,
        int offset,
        int limit,
        List<GarimpoProductResponse> items) {
}
