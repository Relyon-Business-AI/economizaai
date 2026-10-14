# Reestruturação da Taxonomia de Categorias

Status: **em implementação** (iniciado 2026-10-13)
Escopo: backend (`economizaai`) + frontend (`economiza-ai-front`)

## 1. Problema

A taxonomia atual é um **enum flat de 10 valores** (`ProductCategory`), decisão
interna que não segue nenhum padrão externo. Dois problemas medidos em prod
(usuários reais, admins/test excluídos):

- **Baldes grossos demais.** `MEAT_DAIRY` mistura proteína com laticínio (picanha
  e iogurte juntos); `BEVERAGES` mistura alcoólico e não-alcoólico. São os dois
  maiores baldes depois de `GROCERIES` (713 e 281 produtos) e os mais corrigidos.
- **Cobertura ruim.** ~45 das 173 correções de usuário caem em `OTHER` ("nada
  serve") e muitas são `null→X` (motor não categorizou). O `ean_catalog` tem
  **593k entradas sem categoria** porque o `OpenFoodFactsCategoryMapper` colapsa
  a taxonomia rica do OFF em 10 baldes e perde o resto (CLEANING tem só 32 no
  catálogo inteiro).

As **12 custom categories** criadas por usuários reais são, na prática, o voto
deles sobre o que falta:

| Custom criada | Lacuna que denuncia |
|---|---|
| Peixes e Frutos do mar · Carnes Santine · Queijos | `MEAT_DAIRY` grosso demais |
| Temperos e ervas · Legumes · Frutas importadas · Orgânicos | `PRODUCE` grosso demais |
| Porcarias | falta Doces/Snacks |
| Embalagem e derivados · Papel higiênico | falta Casa/Descartáveis |
| Pet | recriaram um enum que já existe → problema de descoberta no app |
| Uber | gasto fora-mercado |

## 2. Causa-raiz do "sempre OTHER"

O seletor de categoria ao confirmar/editar item no app mostra **só os 9 enums
fixos**, não lista as customs existentes, e **não deixa criar inline**. O
endpoint `PUT .../items/{itemId}/category` só aceita enum, não custom id. Pra
usar custom o usuário tem que sair pra Settings → Categorias Personalizadas e
migrar produtos em massa. Resultado: quando nenhum dos 9 serve, a saída é `OTHER`.

→ **Fase 0** resolve isso independente da reestruturação de dados.

## 3. Modelo-alvo: grupo → folha (2 níveis)

Produto é sempre marcado na **folha** (categoria específica). O **grupo** é o
guarda-chuva pra agregação (dashboard). Índice de preço compara por folha.
Custom categories viram folhas household-scoped dentro de um grupo — unifica o
modelo custom e global.

| Grupo | Folhas | Fonte base |
|---|---|---|
| Hortifrúti | Frutas · Legumes e verduras · Temperos e ervas | OFF: fruits-and-vegetables |
| Carnes | Gado · Aves · Suínos · Peixes · Frutos do mar | OFF: fish-meat-eggs |
| Laticínios e ovos | Leite · Queijos · Iogurtes · Ovos · Outros laticínios | OFF: milk-and-dairy |
| Padaria | Pães · Bolos e confeitaria | OFF: breads |
| Mercearia | Grãos e massas · Enlatados e conservas · Molhos e temperos · Matinais | OFF: cereals/composite/fat-sauces |
| Doces e snacks | Doces · Salgadinhos · Chocolates | OFF: sugary/salty-snacks |
| Congelados | Congelados | OFF: frozen-foods |
| Bebidas | Água · Sucos · Refrigerantes · Café e chá | OFF: beverages |
| Bebidas alcoólicas | Cervejas · Vinhos · Destilados | OFF: alcoholic-beverages |
| Limpeza | Limpeza | OPF: cleaning-products |
| Higiene pessoal | Higiene pessoal | OBF: personal-care |
| Casa e descartáveis | Papel · Descartáveis · Utilidades | OPF: household |
| Saúde | Medicamentos · Suplementos | OFF/OPF: supplements |
| Pet | Pet | OPF: pet-food |
| Outros | Outros · Não-mercado | — |

Decisões travadas:
- **Carne é um grupo único** (não dividir a categoria de nível 1); gado/ave/peixe
  são folhas dentro dele. Comparação de preço fina sem poluir o dashboard.
- **Laticínios separado de carne** (resolve iogurte≠picanha).
- Pode começar **flat só nas folhas** e ligar o nível de grupo depois — a tabela
  já suporta.

## 4. Incorporação ao sistema

Trocar o `enum ProductCategory` por uma **tabela `categories`**:

```
categories(id, parent_id NULL, slug UNIQUE, name_pt, name_en, kind['GROUP'|'LEAF'],
           source_hint, sort_order, active, created_at, updated_at)
```

- Enum vira **seed** (migration insere as folhas/grupos canônicos com slugs estáveis).
- `products.category` (enum string) → `products.category_id` (FK categories).
- `household_custom_categories` passa a ser `categories` com escopo household
  (coluna `household_id NULL` = global) OU mantém-se separada apontando pra um
  grupo — decidir na Fase 1. Preferência: unificar.
- Overrides, dicionários, `ean_catalog`, snapshots: `category` string → `category_id`.

## 5. Blast radius (o enum está em ~64 arquivos)

| Camada | Impacto | Ação |
|---|---|---|
| `products`, `curated_dictionary`, `learned_dictionary`, `ean_catalog`, `receipt_items.category_at_confirmation`, `categorization_benchmark_entries`, `household_product_category_overrides`, `consensus_graduation_audit` | 🔴 colunas `VARCHAR(30)` com o nome do enum | migration de-para (precedente: `V36`) |
| DTOs de import (curated/learned/benchmark/ean/set-category/migrate) | 🔴 bindam o enum via Jackson | trocar por slug/id |
| LLM (prompt `values()` + 5× `valueOf()` no caminho Claude) | 🔴 frágil | alimentar prompt pela tabela; parse tolerante |
| `OpenFoodFactsCategoryMapper` | 🟡 regras hardcoded por enum | reescrever p/ PNNS-1/2 + OBF/OPF |
| classify/simulate, live-accuracy, brands | 🟢 baixo | revisar |
| FE: dropdowns, `categories.ts`, picker | 🔴 enum hardcoded | dirigir por `GET /categories` |

**LLM ativo = Claude** (via `AiGateway`); OpenAI/ChatGPT sempre esteve inerte
(sem `OPENAI_API_KEY` em dev/prod). Categorias injetadas dinâmicas no prompt →
**adicionar categoria é de graça**; renomear/split é a parte cara (migração).

## 6. Religar ao OFF/EAN pra recategorizar

- Produtos **com EAN** religam no `ean_catalog` (já populado do OFF).
- ⚠️ **Gap:** o `ean_catalog` guarda só o enum colapsado, **não as `categories_tags`
  cruas**. Pra remapear à taxonomia fina é preciso (a) **persistir as tags cruas**
  e (b) **re-buscar o OFF** pro backlog (temos os EANs; fetch é grátis).
- Produtos **sem EAN** (71/88 no household de teste): recategorização via
  dicionário + re-run do LLM.

## 7. Validação

Rodar em **dev** (DB é cópia), não canário por-user (a mudança é estrutural nos
dados globais, não dá pra ter dois esquemas no mesmo produto). Dados reais do
dono já copiados pro dev (10 recibos / 117 itens) pra ter volume.

## 8. Plano de fases

- **Fase 0 — criação inline no app** (sem tocar dados globais):
  - BE: `PUT .../items/{itemId}/category` aceita `customCategoryId` além de `category`.
  - FE: picker busca `GET /categories` (enums+customs) + botão "criar categoria"
    inline → `POST /categories` → seleciona no item sem sair da nota.
- **Fase 1 — tabela `categories` 2 níveis** + migração de-para das 4+ tabelas;
  enum vira seed; `category` string → `category_id`.
- **Fase 2 — reescrever o OFF mapper** (PNNS-1/2 + OBF/OPF), **persistir tags
  cruas**, re-fetch do backlog, recategorizar via EAN.
- **Fase 3 — promover customs recorrentes** (Temperos, Queijos, Legumes) a folhas
  oficiais; corrigir vazamento p/ `HEALTH`; revalidar golden-set.

## 9. Evidência (prod, usuários reais, 2026-10-13)

- Distribuição global: GROCERIES 974 · MEAT_DAIRY 713 · PRODUCE 466 · BEVERAGES 281
  · CLEANING 246 · BAKERY 185 · (null) 168 · PERSONAL_CARE 151 · HEALTH 106 ·
  OTHER 62 · PET 18.
- 173 overrides de usuários reais (159 enum, 27 custom); ~45 correções → OTHER.
- 12 custom categories em 5 households.
- `ean_catalog`: 593k sem categoria mapeada; CLEANING só 32.
