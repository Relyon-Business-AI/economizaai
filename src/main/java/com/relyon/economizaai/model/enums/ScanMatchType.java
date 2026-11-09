package com.relyon.economizaai.model.enums;

/**
 * Resultado do casamento de um código de barras escaneado (log {@code product_scan}).
 */
public enum ScanMatchType {
    /** Existe um {@code Product} canônico com esse EAN — comparação exata possível. */
    EXACT,
    /** O EAN está no catálogo mas ainda não virou {@code Product} — caímos no tipo genérico. */
    GENERIC,
    /** EAN desconhecido do catálogo e da base. */
    NONE
}
