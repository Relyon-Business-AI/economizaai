# RELEASE — Taxonomia de categorias (V99–V108) + notificações multi-canal

**Status:** PRONTO em dev (suíte backend **2531 verde**, FE `tsc=0`). Baseline prod = **V98**.
**Executar à noite, no go explícito do owner.** Migrations todas aditivas (0 destrutivas), rápidas
(3.403 produtos em prod).

## O que vai no release
- **Taxonomia 2 níveis** (categoria→subcategoria), categoria **custom** com ícone/cor e aninhada
  (sob categoria embutida OU outra custom), **gráfico "Por categoria" override-aware**, admin por
  nó (recategorizar/calibrar/cobertura, editar produto por folha), **motor por nó** (dicionário/
  consenso/ingestão dual-write `category_id`), nome de categoria **único (409)**, `PATCH` de edição,
  nesting travado em 2 níveis, listener do invariante enum↔nó.
- **1 feature não-taxonomia:** notificações multi-canal (migration V102). Já validada no dev.
- **Migrations:** V99–V108 (prod em V98 → gap exato, sem surpresas).

## Ordem OBRIGATÓRIA
**1) Backend (`main`) → 2) FE web (`master`) → 3) Mobile OTA → 4) Popular prod → 5) Smoke.**
⚠️ **Nunca FE antes do backend** — o contrato novo (`hasSubcategories`, `/category-node`, campos
custom) precisa estar no ar antes.

---

## Pré-voo (antes do go)
- [ ] Backend `development` verde: `./mvnw -o test` → BUILD SUCCESS. FE: `npx tsc --noEmit` = 0.
- [ ] `development` no tip pretendido, sem WIP meio-feito (`git log --oneline -5`).
- [ ] Backup recente do DB de prod confirmado (Render faz automático).
- [ ] Janela noturna confirmada.

## 1. Backend → prod (`main`)
```bash
cd ~/Downloads/economizai
git checkout main && git pull --rebase origin main
git merge --no-ff development
git push origin main        # auto-deploy economizai-api-prod (api.economizaai.app)
```
- Flyway aplica **V99–V108** no boot: cria+seed `categories`, backfills V100/V106 (rápidos).
- [ ] Deploy **live** no Render + `GET https://api.economizaai.app/actuator/health` = UP.
- [ ] Confirmar flyway: maior versão aplicada = **108**.

## 2. FE web → prod (`master`)
```bash
cd ~/Documents/projects/economiza-ai-front-fix
git checkout master && git pull --rebase origin master
git merge --no-ff development
git push origin master      # auto-deploy dashboard prod (dashboard.economizaai.app)
```
- [ ] `web-deploy` (master) verde.
- [ ] Hard refresh em dashboard.economizaai.app → categorias novas aparecem.

## 3. Mobile OTA ⚠️ (NÃO pular)
**Motivo:** o app iOS/Android **publicado** lê o `byCategory` antigo (enum `category`); o novo
retorna buckets por **nó** (`category=null`, `slug` preenchido) → o gráfico "Por categoria" do app
antigo quebra. O OTA entrega o JS novo (que entende os nós) sem build de loja.
- Seguir o fluxo de OTA do FE (ver `RELEASE.md` + `scripts/` no repo do front; `eas update` no
  canal de **produção**, a partir do código atual de `development`/`master`).
- ⚠️ **Gotcha:** OTA só alcança o **mesmo runtime/appVersion** (runtime=appVersion). Confirmar o
  canal/runtime certo, senão não chega nos usuários.
- [ ] OTA publicado; abrir o app (build de loja) e conferir que "Por categoria" renderiza.
- Se não for possível o OTA agora → decisão do owner: aceitar o gráfico do app degradado até o
  próximo build de loja.

## 4. Popular prod (base sólida) — admin, pós-deploy, fora de pico
- [ ] Admin → Categorização → **Calibrar taxonomia (dry-run)** → ver precisão do keyword (não escreve).
- [ ] **Recategorizar na taxonomia** → folha fina nos produtos (1.208 EANs via OFF + LLM cap 300/dia).
  Não finaliza tudo no dia 1; o **scheduler diário** completa nos dias seguintes. **Não bloqueia** o
  release (o backfill V100 já deu o nó-grupo, o gráfico já funciona no nível de categoria).

## 5. Smoke em prod
**Backend** (api.economizaai.app):
- [ ] `GET /api/v1/categories/taxonomy` → nós ordenados categoria→subcategoria, campo
  `hasSubcategories`, `outros`/`nao-mercado` por último.
- [ ] `GET /api/v1/insights/spend?from=...&to=...&scope=SUPPORTED` → `byCategory` com `slug`/`label`.
- [ ] Criar categoria + subcategoria + atribuir item → aparece no gráfico.
- [ ] Nome duplicado → **409** (não 500).

**FE web:** picker novo (categoria negrito + subcategoria indentada, ordem lógica), criar com toggle
Categoria/Subcategoria + "+ Nova categoria", gráfico "Por categoria".
**Mobile:** "Por categoria" renderiza.

## Mudanças visíveis em prod (esperado — comunicar se necessário)
- As **195 correções** existentes passam a **refletir no gráfico** (antes eram ignoradas → o gráfico
  desses usuários muda).
- As **12 categorias custom** existentes ficam com **visual padrão** (sem ícone/cor) até serem editadas.
- Toda a UI de categorias muda (categoria-primeiro, breadcrumb, novo fluxo de criar).

## Rollback
Migrations são **aditivas/não-destrutivas** → reverter **só o código**:
- Backend: reverter `main` para o commit anterior ao merge (`git revert` do merge ou reset+`--force-with-lease`)
  + push → Render redeploy. O schema novo **fica** (inofensivo). **Não dropar** as tabelas/colunas novas.
- FE: idem no `master`.
- Sem down-migration. O listener `@PrePersist` some ao reverter o código — sem efeito residual.

## Convenções no release
- `git push` é roteado por diretório (identidade **XandiVieira** automática). Para `gh`:
  `GH_TOKEN=$(gh auth token --user XandiVieira) gh …`. Nunca `gh auth switch`.
- Após o merge, `main` fica **== `development`** em código (prod não regride).
- `main` = branch de prod do BACKEND; `master` = branch de prod do FE (nomes invertidos de propósito).
