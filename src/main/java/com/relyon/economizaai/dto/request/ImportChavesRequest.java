package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Bulk import of receipts from their access keys — the chaves a user exports from
 * the Nota Fiscal Gaúcha portal (or any list). Each RS chave is reconsulted on a
 * public SEFAZ portal and ingested; see {@code docs/ONBOARDING_IMPORT.md}.
 */
public record ImportChavesRequest(
        @NotEmpty
        @Size(max = 500, message = "no máximo 500 chaves por importação")
        List<String> chaves
) {}
