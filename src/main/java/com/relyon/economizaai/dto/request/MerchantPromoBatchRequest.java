package com.relyon.economizaai.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** ERP-friendly JSON batch — same per-row semantics as the CSV/XLSX import. */
public record MerchantPromoBatchRequest(
        @NotEmpty List<@Valid MerchantPromoRequest> promos
) {}
