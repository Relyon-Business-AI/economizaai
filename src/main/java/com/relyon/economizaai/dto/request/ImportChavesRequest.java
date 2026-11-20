package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.service.ReceiptBatchLimit;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Bulk import of receipts from their access keys — the chaves a user exports from
 * the Nota Fiscal Gaúcha portal (or any list). Each RS chave is reconsulted on a
 * public SEFAZ portal and ingested; see {@code docs/ONBOARDING_IMPORT.md}.
 *
 * <p>The business limit of chaves per request is an admin setting, checked by
 * {@link ReceiptBatchLimit}; the annotation is only the technical ceiling of a payload.
 */
public record ImportChavesRequest(
        @NotEmpty
        @Size(max = ReceiptBatchLimit.HARD_CEILING)
        List<String> chaves
) {}
