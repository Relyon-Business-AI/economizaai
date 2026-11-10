package com.relyon.economizaai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Ver todos os [tipo]" — comparação genérica do scanner: uma LISTA PLANA de ofertas de
 * todos os produtos do mesmo tipo ({@code generic_name_norm}, ex.: "creme de leite"), uma
 * linha por (produto, mercado), do mais barato ao mais caro. O produto pode aparecer várias
 * vezes (um mercado por linha). Marcas e tamanhos diferentes: é referência de FAIXA de preço,
 * NÃO o mesmo produto. K-anônimo por tier (mercearia n=1, farmácia/saúde K=3, fail-closed).
 *
 * <p>Escopo: cascata explícita "faixa de referência" — raio perto de você (10 km) →, se
 * poucas ofertas, ALARGA pra UF → nacional (diferente do best-markets de um produto, que
 * para de propósito no raio).
 */
public record GenericComparisonResponse(
        @Schema(description = "Nome do tipo para o cabeçalho, ex.: \"Creme de Leite\"")
        String typeName,
        @Schema(description = "Produto exato que o usuário escaneou (destacado na lista)")
        UUID scannedProductId,
        @Schema(description = "Qual recorte da cascata foi usado: NEARBY, STATE ou NATIONAL")
        String scope,
        @Schema(description = "Ofertas achatadas, uma por (produto, mercado), do mais barato ao mais caro")
        List<GenericOffer> offers
) {
    /** Uma oferta: um produto (marca+tamanho) visto num mercado, com o preço mais recente ali. */
    public record GenericOffer(
            UUID productId,
            @Schema(description = "Rótulo do produto para a linha: marca + tamanho, ex.: \"Nestlé 200g\"")
            String productName,
            String brand,
            String packLabel,
            BigDecimal unitPrice,
            @Schema(description = "CNPJ do mercado (14 dígitos); chave para o histórico de preço por mercado")
            String marketCnpj,
            @Schema(description = "Razão social completa do mercado (mostrada nos detalhes)")
            String marketName,
            @Schema(description = "Nome amigável do mercado para a linha (apelido do lar → rede → razão social limpa)")
            String marketFriendlyName,
            String city,
            String state,
            @Schema(description = "Distância em km do usuário até o mercado; null quando não há coordenadas")
            Double distanceKm,
            LocalDateTime observedAt,
            String channel,
            @Schema(description = "True quando esta oferta é do produto exato que o usuário escaneou")
            boolean scanned,
            @Schema(description = "True para a oferta de MENOR preço da lista — badge persistente \"Mais barato\" (independe da ordenação)")
            boolean isCheapest,
            @Schema(description = "True para a oferta MAIS PERTO do usuário — badge persistente \"Mais perto\" (null distance nunca é a mais perto)")
            boolean isNearest
    ) {
        /** Copy com os badges persistentes definidos (preço/distância são decididos sobre a lista inteira). */
        public GenericOffer withBadges(boolean cheapest, boolean nearest) {
            return new GenericOffer(productId, productName, brand, packLabel, unitPrice, marketCnpj,
                    marketName, marketFriendlyName, city, state, distanceKm, observedAt, channel, scanned,
                    cheapest, nearest);
        }
    }
}
