package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;

/**
 * Coordenada aproximada resolvida a partir de um CEP, para pré-visualização antes
 * de salvar a localização ({@code PATCH /users/me/location}). {@code label} é um
 * rótulo amigável ("Cidade - UF") só para exibição.
 */
public record CepLocationResponse(BigDecimal latitude, BigDecimal longitude, String label) {
}
