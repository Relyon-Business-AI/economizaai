# Preço de combustível — candidata a feature (design inicial)

**Status (2026-11-03):** NÃO construída. Terreno preparado: segmento `GAS_STATION`
(CNAE 4731/4732) classifica postos automaticamente (V128 reclassificou os
existentes), tirando-os da fila de revisão e marcando-os à parte. As notas de
posto ficam guardadas normalmente — um backfill (mesmo mecanismo do botão
"Mercado") gera as observações retroativas quando a feature nascer.

**Mockups (2026-11-03) em `docs/mockups/` — decisão de UX em aberto com a FE dev:**
- `fuel-variante-a-tile.html` — entrada por TILE grande "Combustível" na home.
- `fuel-variante-b-chips.html` — escopo discreto por CHIPS no card de gasto.
- `fuel-variante-c-escopos.html` — **favorita do dono (2026-11-03)**: escopos
  PROGRESSIVOS na home real (`Mercado (default) · Farmácia · Combustível ·
  Outras · Tudo`): as abas NEM EXISTEM até o household ter nota daquele tipo
  (escaneou farmácia → nasce a aba Farmácia + Tudo; posto → Combustível; etc.).
  Na lente Combustível as métricas trocam (abastecimentos, R$/L) e a ENTRADA do
  comparador de postos aparece ali — sem tile na home, sem roubar foco de
  mercado. Direção: 1 identidade (mercado) · escopos contextuais · 1 tela
  satélite só pra combustível. Infra já existente: componente `ScopeFilter`
  (chips+banner, hoje atrás de `splitVisibleFor`=admin) e filtro por segmento
  no backend. ATENÇÃO no rollout: hoje o usuário comum vê escopo TUDO fixo —
  default virar Mercado muda o número do hero pra quem tem notas de outros
  tipos (planejar transição/banner).
  As telas internas (comparação + detalhe do posto) são iguais em A/B/C e já
  foram aprovadas pelo dono. Abrir direto no navegador.

## Por que vale
- Muita gente já escaneia nota de posto (observação do dono na moderação).
- O cupom de posto tem dado estruturado perfeito: "GASOLINA COMUM", quantidade
  em LITROS, preço por litro — mais limpo que item de mercado.
- Diferencial vs. apps de combustível: nosso preço vem de NOTA FISCAL REAL
  (selo "✓ nota real"), não de crowdsourcing manual desatualizado.
- Comparação de combustível é hábito mais forte que a de mercado → retenção.

## Forma proposta (MVP)
1. **Índice próprio** (R$/litro), NUNCA misturado com mercearia. Fuel type
   derivado da descrição do item (GASOLINA COMUM/ADITIVADA, ETANOL, DIESEL
   S10/S500, GNV) + unidade L.
2. **Entrada separada na home** (tile "Combustível" ao lado de Mercados) —
   ver mockup 1; a tela segmenta por combustível (mockup 2).
3. **Cards**: mais barato no raio, lista de postos (distância, R$/L, recência,
   nº famílias), banner "etanol vale a pena?" (regra dos 70%).
4. **Detalhe do posto**: histórico do litro 90d, outros combustíveis, alerta
   "avise-me quando baixar" (reusa notification rules).
5. **Privacidade**: preço de combustível é fato público (ANP publica) — tier
   mercearia (n=1, dia truncado) se aplica bem.

## Depois do MVP
- Custo por km / tanque cheio; integração com a feature de rotas de compra.
- Monetização: postos entram no MESMO funil merchant (claim do CNPJ, perfil,
  preço anunciado verificado) — o portal do lojista já serve.
