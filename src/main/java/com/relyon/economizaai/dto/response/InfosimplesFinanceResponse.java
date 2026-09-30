package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Admin panel of the Infosimples prepaid account: live state (saldo, consumo
 * COBRADO do mês, franquia), what the franchise will sweep at the next close
 * (day 1 of {@code proximoFechamento}), lifetime totals and the month ledger.
 * Live fields are null when the provider is disabled/unreachable.
 */
public record InfosimplesFinanceResponse(
        BigDecimal saldo,
        BigDecimal consumoMes,
        BigDecimal franquiaMinima,
        BigDecimal aVarrerNoFechamento,
        LocalDate proximoFechamento,
        BigDecimal totalRecarregado,
        BigDecimal totalConsumido,
        BigDecimal totalVarrido,
        List<MonthLine> historico
) {
    public record MonthLine(String month, BigDecimal recarga, BigDecimal consumo,
                            BigDecimal varrido, boolean closed) {}
}
