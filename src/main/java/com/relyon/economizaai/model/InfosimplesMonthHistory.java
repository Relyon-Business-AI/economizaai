package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One month of the Infosimples account ledger: recargas feitas, consumo COBRADO
 * (extrato real, not our estimate) and quanto a franquia mínima varreu no
 * fechamento (day 1 of the following month, ~06h). Jul-set/2026 were seeded from
 * the painel extratos; from October on the scheduled jobs fill it automatically.
 */
@Entity
@Table(name = "infosimples_month_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class InfosimplesMonthHistory extends BaseEntity {

    /** ISO month, e.g. {@code 2026-09}. */
    @Column(nullable = false, unique = true, length = 7)
    private String month;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal recarga;

    /** Null while the month is still open. */
    @Column(precision = 12, scale = 2)
    private BigDecimal consumo;

    /** Franchise sweep applied at close; null while open. */
    @Column(precision = 12, scale = 2)
    private BigDecimal varrido;

    @Column(nullable = false)
    private boolean closed;
}
