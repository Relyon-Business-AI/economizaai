package com.relyon.economizaai.model.enums;

import java.util.Set;

/**
 * A LENTE do gasto pessoal: qual recorte de segmentos uma consulta de
 * spend/insights/notas/itens cobre. É puramente a visão PESSOAL — o índice
 * colaborativo e o gate de merchant (que seguem tratando farmácia como
 * suportada) não usam este enum.
 *
 * <p>As queries só entendem três modos (IN / NOT IN / ALL); cada lente se
 * traduz centralmente aqui em (conjunto de segmentos, inclusão/exclusão) via
 * {@link #segments()} / {@link #exclusion()} / {@link #queryMode()} — os sites
 * de consulta nunca enumeram lentes.
 *
 * <ul>
 *   <li>{@code ALL} — todas as notas ("Tudo").</li>
 *   <li>{@code MARKET} — mercado SEM farmácia (supermercado, atacarejo, varejo de alimentos). Default da lente no app.</li>
 *   <li>{@code PHARMACY} — só farmácias.</li>
 *   <li>{@code FUEL} — só postos (GAS_STATION).</li>
 *   <li>{@code OTHERS} — tudo que não é mercado/farmácia/combustível ("Outras notas").</li>
 *   <li>{@code SUPPORTED}/{@code OTHER} — valores LEGADOS (mercado+farmácia vs resto), mantidos
 *       por compatibilidade com clientes antigos; o app novo não os envia.</li>
 * </ul>
 */
public enum MarketScope {
    ALL(Set.of(), false),
    SUPPORTED(Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.PHARMACY, MerchantSegment.FOOD_RETAIL), false),
    OTHER(Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.PHARMACY, MerchantSegment.FOOD_RETAIL), true),
    MARKET(Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.FOOD_RETAIL), false),
    PHARMACY(Set.of(MerchantSegment.PHARMACY), false),
    FUEL(Set.of(MerchantSegment.GAS_STATION), false),
    OTHERS(Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.FOOD_RETAIL,
            MerchantSegment.PHARMACY, MerchantSegment.GAS_STATION), true);

    private final Set<MerchantSegment> segments;
    private final boolean exclusion;

    MarketScope(Set<MerchantSegment> segments, boolean exclusion) {
        this.segments = segments;
        this.exclusion = exclusion;
    }

    /** Segmentos cujos CNPJs formam o conjunto IN/NOT-IN da lente (vazio para ALL). */
    public Set<MerchantSegment> segments() {
        return segments;
    }

    /** True quando a lente EXCLUI o conjunto (OTHER/OTHERS); false quando inclui. */
    public boolean exclusion() {
        return exclusion;
    }

    /** Lente de inclusão (filtra PARA DENTRO do conjunto de segmentos)? */
    public boolean inclusion() {
        return this != ALL && !exclusion;
    }

    /**
     * True quando a lente deve ABSORVER merchants com override SUPPORTED (admin promoveu um
     * CNPJ de CNAE não-resolvido a "mercado suportado"): só as lentes que incluem mercado
     * (SUPERMARKET/FOOD_RETAIL). Lentes específicas (PHARMACY/FUEL) NÃO herdam o override —
     * senão um supermercado promovido vazaria pra Farmácia (bug real: JL SUPERMERCADOS,
     * segment=UNKNOWN+override=SUPPORTED, aparecia na lente Farmácia).
     */
    public boolean absorbsSupportOverride() {
        return segments.contains(MerchantSegment.SUPERMARKET)
                || segments.contains(MerchantSegment.FOOD_RETAIL);
    }

    /**
     * O modo que as queries entendem ({@code 'ALL'|'SUPPORTED'|'OTHER'}): toda
     * lente de inclusão vira 'SUPPORTED', toda exclusão vira 'OTHER' — o
     * conjunto de CNPJs passado junto é o que muda o significado.
     */
    public String queryMode() {
        if (this == ALL) return "ALL";
        return exclusion ? "OTHER" : "SUPPORTED";
    }
}
