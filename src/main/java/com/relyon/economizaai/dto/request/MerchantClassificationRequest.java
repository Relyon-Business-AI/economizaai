package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.MerchantSegment;

/**
 * Veredito manual do admin sobre um merchant cinza. O {@code segment} define a lente:
 * {@code SUPERMARKET} (Mercado), {@code PHARMACY} (Farmácia), {@code GAS_STATION} (Combustível)
 * ou {@code OTHER} (Bloquear — sai do índice). O serviço ajusta o override e o índice a partir
 * do segmento; nunca é {@code UNKNOWN}.
 */
public record MerchantClassificationRequest(MerchantSegment segment) {
}
