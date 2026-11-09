package com.relyon.economizaai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Ver todos os [tipo]" — comparação genérica do scanner: todos os produtos do mesmo
 * tipo ({@code generic_name_norm}, ex.: "creme de leite") com o melhor preço perto do
 * usuário, do mais barato ao mais caro. Marcas e tamanhos diferentes: é referência de
 * faixa de preço, NÃO o mesmo produto. Mesmo recorte k-anônimo do best-markets.
 */
public record GenericComparisonResponse(
        @Schema(description = "Nome do tipo para o cabeçalho, ex.: \"Creme de Leite\"")
        String typeName,
        @Schema(description = "Produto exato que o usuário escaneou (destacado na lista)")
        UUID scannedProductId,
        List<GenericProductRow> products
) {
    /** Uma marca/tamanho do tipo, com seu melhor preço perto do usuário (ou sem preço ainda). */
    public record GenericProductRow(
            UUID productId,
            String label,
            String brand,
            String packLabel,
            boolean scanned,
            boolean hasPrice,
            BigDecimal price,
            BigDecimal typicalPrice,
            String marketName,
            LocalDateTime observedAt,
            int sampleCount,
            long distinctHouseholds
    ) {}
}
