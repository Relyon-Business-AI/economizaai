# Aposentadoria do `ProductCategory` (enum legado)

Estado do refactor "o nó é a verdade" e o runbook da remoção final. Vocabulário:
**categoria** = nível 1 da taxonomia (15), **subcategoria** = folha.

## Fases

| Fase | O quê | Status |
|---|---|---|
| A | Nó vira a fonte da verdade; enum vira projeção derivada | ✅ V113 (2026-10-17) |
| B | Lógica interna decide por nó/slug, não por valores do enum | ✅ (mesma leva) |
| C | API aceita `categorySlug` em todos os filtros; FE/mobile migra | ✅ backend; FE principal já usa slug |
| D | Drop das colunas enum + delete do enum e da ponte | ⏳ GATED (ver abaixo) |

## O que as fases A–C mudaram

- **Listener node-first** (`ProductCategoryNodeListener`): com nó, o enum é derivado
  (`CategoryResolver.deriveCoarseEnum`); sem nó, o enum ainda dá o piso (nó-grupo).
- **Escritores de enum** (intenção = categoria grossa) movem o nó junto via
  `CategoryResolver.reconcileNode` — admin setCategory, update de produto, LLM enrich/
  disagreement, consenso, farmácia, correção de item, reset. **Regra pra código novo:
  escreva o NÓ; se receber enum da borda, converta com `reconcileNode`.**
- **V113**: backfill do nó-grupo em `products`/`curated_dictionary_entries`/
  `learned_dictionary`/`ean_catalog` + `receipt_items.category_node_at_confirmation`
  (snapshot do nó no confirm, par do enum).
- **Filas/filtros internos por nó**: fila de revisão de categoria (`findNeedingCategoryReview`
  = sem nó OU nó 'outros'), candidatos de subcategoria (grupos COM subcategoria, via
  `groupIdsWithoutSubcategories`), recategorizador.
- **API**: `categorySlug` em `/items`, `/insights/query`, `/receipts`, `/admin/products`
  (`categoryNodeSlug`). Paridade: slug `outros` ≡ enum `OTHER` (inclui itens sem produto).
  Os params/DTOs de enum continuam funcionando (projeção).

## Fase D — o delete final (runbook)

**Gate: só depois que o FE web E o app móvel LANÇADO não dependerem mais de nenhum campo/param
de enum.** Apps já instalados leem `category` nos responses — remover antes quebra usuários.
Checklist de pré-requisitos:

1. FE: todo filtro envia `categorySlug`; todo display usa `categoryNodeSlug/Label`
   (fallbacks pro enum removidos); publicado via OTA **e** build de loja, com janela de adoção.
2. Export/relatórios (CSV/XLSX/WhatsApp) migrados pra rótulo do nó.
3. Consenso re-arquitetado pra votar por NÓ (hoje o tally de
   `HouseholdProductCategoryOverride.category` é por enum).
4. Overrides de enum migrados: `household_product_category_overrides.category` → nó-grupo.
5. `ReceiptItem.categoryAtConfirmation` substituído por `categoryNodeAtConfirmation`
   em TODA leitura (ItemQueryService/insights/aggregados por snapshot).

**Migration de drop (rascunho — NÃO criar antes do gate):**
```sql
ALTER TABLE products DROP COLUMN category;
ALTER TABLE curated_dictionary_entries DROP COLUMN category;
ALTER TABLE learned_dictionary DROP COLUMN category;
ALTER TABLE ean_catalog DROP COLUMN category;
ALTER TABLE household_product_category_overrides DROP COLUMN category;        -- após migrar pra nó
ALTER TABLE receipt_items DROP COLUMN category_at_confirmation;               -- após FE ler o nó
ALTER TABLE consensus_graduation_audit DROP COLUMN category;                  -- após consenso por nó
ALTER TABLE categorization_benchmark_entries DROP COLUMN expected_category;   -- benchmark por slug
ALTER TABLE categories DROP COLUMN legacy_enum;                               -- a ponte morre por último
```

**Código a deletar na D:** enum `ProductCategory`; ponte no `CategoryResolver`
(`idForLegacyEnum`/`legacyEnumForNode`/`deriveCoarseEnum`/`reconcileNode` com enum);
derivação de enum no listener; params `category` (enum) dos controllers; campos enum dos
DTOs de request/response; `categoryList()` dos prompts de IA coarse.
