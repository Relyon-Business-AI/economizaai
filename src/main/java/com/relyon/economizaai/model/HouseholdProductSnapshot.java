package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Snapshot por domicílio do melhor preço de um produto NO MOMENTO da consulta, pra o histórico
 * de "produtos escaneados" mostrar um delta "mudou de preço desde a última consulta" sem
 * recalcular preço ao vivo pra cada linha (o FE busca o ao vivo por item visível).
 *
 * <p>Chave: {@code productId} quando um produto canônico foi resolvido, senão o tipo
 * ({@code genericNameNorm}) pra um casamento só-de-prévia-de-catálogo. {@code snapshotKey}
 * unifica os dois numa chave única e não-nula por domicílio (ver V140).
 */
@Entity
@Table(name = "household_product_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uq_household_product_snapshot",
                columnNames = {"household_id", "snapshot_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HouseholdProductSnapshot extends BaseEntity {

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    /** Produto canônico resolvido, ou null quando só a prévia de catálogo (tipo) casou. */
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "generic_name_norm", length = 160)
    private String genericNameNorm;

    /** {@code productId} quando presente, senão {@code "type:" + genericNameNorm} — ver {@link #keyFor}. */
    @Column(name = "snapshot_key", nullable = false, length = 200)
    private String snapshotKey;

    /** Melhor preço unitário visto na consulta (menor/mediana do escopo servido), null se bloqueado. */
    @Column(name = "last_best_unit_price", precision = 12, scale = 2)
    private BigDecimal lastBestUnitPrice;

    /** Escopo da consulta que produziu o preço: NEARBY | STATE | NATIONAL. */
    @Column(name = "last_best_scope", length = 20)
    private String lastBestScope;

    @Column(name = "scan_count", nullable = false)
    @lombok.Builder.Default
    private int scanCount = 1;

    @Column(name = "last_consulted_at", nullable = false)
    private OffsetDateTime lastConsultedAt;

    /** Chave estável: preferir o produto; senão o tipo normalizado; senão null (nada pra chavear). */
    public static String keyFor(UUID productId, String genericNameNorm) {
        if (productId != null) return productId.toString();
        if (genericNameNorm != null && !genericNameNorm.isBlank()) return "type:" + genericNameNorm;
        return null;
    }
}
