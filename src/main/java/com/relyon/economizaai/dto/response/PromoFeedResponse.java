package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.priceindex.CommunityPromoService.CommunityPromo;

import java.util.List;

/**
 * Feed de promoções com o CONTEXTO de localização para a cascata do app:
 *
 * <ul>
 *   <li>{@code locationSource} — {@code EXPLICIT} (o usuário definiu), {@code INFERRED}
 *       (estimado pelas compras) ou {@code NONE}. Quando != NONE, cada promo traz
 *       {@code distanceKm} e o app recorta por raio; em NONE o app usa seguidos → UF → tudo.</li>
 *   <li>{@code uf} — UF majoritária das notas do usuário (fallback de estado), ou null.</li>
 *   <li>{@code promos} — lista completa; o app faz o recorte/"ver mais" no cliente.</li>
 * </ul>
 */
public record PromoFeedResponse(String locationSource, String uf, List<CommunityPromo> promos) {
}
