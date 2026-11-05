package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Edição do mercado de uma nota de FOTO (dado não verificado). {@code cnpj} é opcional —
 * só dígitos (14) quando informado; vazio/null limpa.
 */
public record UpdateReceiptMarketRequest(@NotBlank String marketName, String cnpj) {
}
