package com.relyon.economizaai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma linha do histórico de "produtos escaneados" do domicílio: o snapshot gravado do melhor
 * preço NO MOMENTO da última consulta. Sem preço ao vivo — o FE busca o atual por item visível
 * (endpoints de comparação) e calcula o delta "mudou de preço desde a última consulta".
 */
public record ScannedProductResponse(
        @Schema(description = "Produto canônico; null quando só a prévia de catálogo (tipo) casou")
        UUID productId,
        @Schema(description = "Nome amigável do produto (apelido do lar → nome do produto → tipo)")
        String productName,
        @Schema(description = "Rótulo do tipo, ex.: \"Creme de Leite\"")
        String typeLabel,
        @Schema(description = "Quando este produto/tipo foi consultado pela última vez")
        OffsetDateTime lastConsultedAt,
        @Schema(description = "Melhor preço unitário gravado na última consulta; null se bloqueado por k-anon")
        BigDecimal snapshotBestPrice,
        @Schema(description = "Escopo da consulta que gerou o preço: NEARBY | STATE | NATIONAL")
        String snapshotScope,
        @Schema(description = "Quantas vezes o domicílio consultou este produto/tipo")
        int scanCount
) {}
