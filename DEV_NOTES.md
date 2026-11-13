# economizai — dev shortcuts to revisit before prod

Things we shipped that work for development / early users but need an
upgrade before we have real volume / real revenue / real privacy stakes.
Each entry: **what's there now**, **why it's OK for dev**, **what to
change before prod**, **rough effort**.

When in doubt: search the codebase for `// TODO(prod)` markers — they
mirror entries here.

---

## Release 2026-11-13 — Sergipe: adapter nativo com layout do DANFE ainda não verificado

### SE: fetch verificado ao vivo, DANFE parseado provisoriamente pelo parser responsivo
- **Now**: `SergipeNfcePortalAdapter` foi construído e verificado AO VIVO contra o portal
  `nfce.sefaz.se.gov.br` até o POST: forma de consulta, sessão (JSESSIONID), Cloudflare Turnstile
  (sitekey `0x4AAAAAADPVesX-QLrY8PWn`), e o endpoint `consultarNFCeDCA.jsp` rejeitando "Captcha inválido"
  sem token (data de emissão confirmada OPCIONAL). O que NÃO foi possível fechar: renderizar o DANFE real
  pós-Turnstile — a chave do CapSolver é segredo só no Render e não é acessível neste ambiente de build, e
  as 3 chaves de teste não têm `issued_at`/data no banco. Então `parseHtml` usa provisoriamente
  `ResponsiveDanfeParser` (mesmo parser de RS/PR/SP/BA), que pode não bater com o layout específico de SE.
- **Por que OK por ora**: a cadeia de ingestão tem rede de segurança — um DANFE de SE que o parser não leia
  cai no resgate por LLM (lê o HTML cru) e, se existir, no Infosimples. Pior caso é `FAILED_PARSE`
  honesto, não pior que o status atual (SE estava 100% quebrado com Infosimples 615). Há fixtures reais do
  FORMULÁRIO e da página de rejeição em `src/test/resources/fixtures/sefaz/se/` cobrindo a camada de fetch.
- **Antes de confiar em SE**: pegar UMA NFC-e real de SE, resolver o Turnstile com a chave do CapSolver
  (no Render), salvar o DANFE renderizado como `src/test/resources/fixtures/sefaz/se/danfe.html`, escrever
  o teste de parser (padrão `RealSantaCatarinaFixtureTest`) e, se o layout diferir do responsivo, criar um
  parser dedicado de SE. Kill switch enquanto isso: `SEFAZ_SE_ENABLED=false`.
- **Esforço**: ~1–2 h depois de ter uma nota real + solver ligado.

## Release 2026-11-11 — lacunas conhecidas, enviadas de propósito

### XML de NF-e: verificação de assinatura DESLIGADA até existir o bundle de certificados
- **Now**: nota vinda de XML enviado pelo usuário só entra no índice ONLINE com autenticidade
  comprovada (`XmlIndexEligibilityService`). O caminho por assinatura digital do emitente
  (`NfeXmlSignatureVerifier`) precisa das raízes + intermediárias da ICP-Brasil num arquivo PEM
  apontado por `NFE_XML_TRUST_BUNDLE_PATH`. A variável vem vazia: sem âncora, **nenhuma
  assinatura é aceita** e todo XML online é confirmado pela reconsulta paga da chave
  (Infosimples — mesmo limite por usuário, teto diário e ledger; `NFE_XML_PAID_RECONSULT_ENABLED`).
  XML de loja física nunca alimenta o índice.
- **Por que OK por ora**: falha fechada — sem prova, a nota fica só no histórico pessoal.
- **Antes de escalar XML**: montar o PEM com as cadeias ICP-Brasil vigentes, publicar no host e
  setar a variável; validar com XMLs reais. Enquanto isso cada XML online custa uma consulta paga.

### Aviso de custo desiste depois de 24 h
- **Now**: o aviso de teto/saldo (`CostAlertService`) tenta o WhatsApp do admin, manda e-mail
  depois de `COST_ALERT_EMAIL_FALLBACK_AFTER` falhas e segue tentando; passadas **24 h** sem
  nenhum canal aceitar, a linha vira `EXPIRED` e só fica o log de erro `cost_alert.expired`.
- **Revisitar**: se WhatsApp e SMTP estiverem fora ao mesmo tempo por um dia inteiro, o aviso
  se perde. Um terceiro canal (ou alerta em cima do log) fecha o buraco.

### Tetos de custo viram o dia em UTC (21h de Brasília)
- **Now**: o gasto "de hoje" de cada serviço e os limites por usuário contam por dia **UTC** —
  a virada é às 21h de Brasília, não à meia-noite local. Um teto estourado à tarde libera às 21h.
- **Revisitar**: se a virada no meio da noite brasileira confundir (suporte / leitura do
  painel), trocar a janela para o dia de Brasília (`BrazilClock`) em guard, monitor e painel juntos.

### `GET /sefaz/state-status`: heurística de "UNSTABLE" sem calibragem
- **Now**: um estado verificado aparece como `UNSTABLE` quando está na lista de portais
  pausados, quando o parser falha em rajada, ou quando nas últimas `SEFAZ_STATE_STATUS_WINDOW_HOURS`
  (24) houve pelo menos `SEFAZ_STATE_STATUS_MIN_FAILURES` (3) falhas de portal e elas superam as
  notas lidas. Os números são chute inicial, nunca medidos contra tráfego real.
- **Revisitar**: estado de pouco volume pode piscar `UNSTABLE` com 3 falhas; estado grande pode
  demorar a aparecer. Ajustar pelas variáveis depois de umas semanas de campanha.

### Lifecycle do R2 para `photo-attempts/` ainda não existe (ação do dono)
- **Now**: a exclusão de conta apaga a foto de perfil e as fotos de nota do armazenamento
  (`AccountStorageCleaner`, melhor esforço, depois do commit). A rede de segurança prevista — uma
  regra de expiração no bucket — **não foi criada**.
- **Fazer (dono, no painel da Cloudflare)**: regra de lifecycle de **30 dias restrita ao prefixo
  `photo-attempts/`**. Não aplicar ao bucket inteiro: as fotos de perfil ficam na raiz do mesmo
  bucket e não podem expirar.

### Mercado Pago: campos de `authorized_payments` não conferidos com payload real
- **Now**: a renovação mensal lê `preapproval_id`, `payment.id`, `payment.status`,
  `transaction_amount` e `debit_date` de `GET /authorized_payments/{id}`
  (`MercadoPagoClient.fetchAuthorizedPayment`), escritos a partir da documentação.
- **Antes de cobrar na web**: conferir com um evento real de sandbox (junto com a assinatura
  `x-signature`, ver "Billing" abaixo). Campo com outro nome = renovação não avança o período; a
  carência de 3 dias dá tempo de notar.

### `reWriteBatchedInserts` fora da URL do JDBC
- **Now**: o Hibernate manda lotes (`jdbc.batch_size=50`, `order_inserts/updates`), mas a
  `DATABASE_URL` não tem `reWriteBatchedInserts=true`, então o driver do Postgres ainda envia um
  comando por linha dentro do lote (menos idas e voltas, não o ganho total).
- **Quando**: acrescentar `?reWriteBatchedInserts=true` à `DATABASE_URL` no Render (dev primeiro)
  e medir a confirmação de uma nota grande.

### HTTPS obrigatório nos portais SEFAZ não dá para exigir
- **Now**: vários estados imprimem QR com URL `http://` (alguns portais só respondem assim), então
  `SEFAZ_ALLOW_PLAIN_HTTP` fica `true` por padrão. O que protege é a lista de hosts permitidos,
  conferida de novo a cada redirecionamento (`PortalRedirects`), não o esquema.
- **Revisitar**: `SEFAZ_ALLOW_PLAIN_HTTP=false` só quando todos os estados atendidos servirem https.

### HTML da nota em tabela separada — etapa 1
- Etapa 1: o HTML mora em `receipt_raw_html`; a coluna antiga sai numa release seguinte.

---

## Retenção das fotos de extração em R2 — TEMPORÁRIO (2026-11-08)
- **Now**: toda tentativa de extração por foto guarda as imagens enviadas em R2
  (`photo-attempts/<receiptId>/<idx>.<ext>` + `manifest.json`), ligado por padrão
  (`PHOTO_RETENTION_ENABLED=true`). Serve pra re-rodar o modelo com as MESMAS fotos
  enquanto afinamos prompt/modelo. Recuperação ADMIN-only:
  `GET /api/v1/admin/photo-attempts/{receiptId}` (lista) e `/{index}` (bytes).
- **Por que OK por ora**: volume baixo, só pra debugar o extrator; é best-effort
  (nunca quebra a ingestão) e as imagens são do próprio usuário (não entram no índice).
- **Atualização (2026-11-11)**: a exclusão de conta já apaga as fotos da conta
  (`AccountStorageCleaner`). O TTL segue pendente — regra de lifecycle de 30 dias no prefixo
  `photo-attempts/`, a criar pelo dono (ver "Release 2026-11-11" acima).
- **Mudar antes de prod "pra valer"**: DECIDIR se mantém. Se não, **desligar**
  (`PHOTO_RETENTION_ENABLED=false` ou toggle admin `POST /photo-attempts/settings`) e
  **apagar o prefixo `photo-attempts/` no R2**. Se mantiver, precisa de: retenção/TTL
  (lifecycle rule no bucket), consentimento/privacidade (LGPD — são fotos de compras),
  e talvez só guardar em FALHA. Hoje guarda SEMPRE e, sem a regra, pra SEMPRE. Toggle admin é
  em-memória (reseta no restart pro default do env) — a env var é a fonte durável.
- **Esforço**: baixo pra desligar; médio pra tornar permanente com TTL+consentimento.

## Dev no plano Free do Render + db-dev mínimo (2026-11-03)
- **Now**: `economizaai-api-dev` roda no plano **Free** (512 MB, CPU mínima, dorme após 15 min
  sem tráfego) e `economizaai-db-dev` é Basic 256 MB / 1 GB de disco (recriado do dump; o
  antigo Basic 1 GB / 15 GB foi apagado). Custo do dev caiu de ~US$ 48 pra ~US$ 6/mês.
- **Como cabe em 512 MB**: env var `JAVA_TOOL_OPTIONS` no serviço dev
  (`-Xmx220m -XX:MaxMetaspaceSize=150m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m
  -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1`). `-Xmx` explícito vence o
  `MaxRAMPercentage=50` do Dockerfile. Memória em repouso fica em ~500 MB: qualquer carga real
  (parse pesado, OCR) pode OOM-killar o dev.
- **Profile `lowmem` (2026-11-10)**: o app deixou de caber (OOM de Metaspace ~1 min após o
  boot, em loop). Em vez de subir limites de novo, o dev roda `SPRING_PROFILES_ACTIVE=dev,lowmem`
  (`application-lowmem.yaml`): lazy-init (@Scheduled segue eager), backfills de boot desligados
  (`economizaai.startup-backfills.enabled=false`), jobs com initial-delay de 10 min+ (o dev dorme
  em 15, então quase nunca rodam lá), Tomcat 20 threads, Hikari 5. Flags da JVM atuais:
  `-Xmx240m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=32m` (resto igual). Medido em
  2026-11-10 com lowmem: 256/128 estoura Metaspace no 1º cadastro; 216/176 estoura heap logo após
  o boot; 240/160 passou no smoke test do domicílio. **Não há mais folga** nos 512 MB. Efeito
  colateral: o 1º request de cada endpoint é mais lento (beans criados sob demanda) e erros de
  wiring só aparecem no uso — `LowMemProfileContextTest` resolve todos os beans pra pegar isso.
  Se voltar a estourar mesmo assim, o caminho é plano maior, não mais flags.
- **OK for dev**: quase não usamos o dev; o cold start de ~3 min só atinge autofix/E2E
  (health wait precisa tolerar) e quem abrir o dashboard-dev depois de 15 min parado.
- **Before prod / revisitar**: prod NÃO usa nada disso (segue Standard 2 GB). Se o dev virar
  gargalo (OOM em loop, E2E timeout), voltar pra Standard + agendar suspend/resume via API
  (`POST /services/{id}/suspend|resume`) em vez de Free. Paid→Free não é permitido pela API,
  por isso o serviço foi recriado (id novo `srv-dbklrqnf3r2c73f93cdg`, onrender
  `economizaai-api-dev-free.onrender.com`); env vars via API NÃO disparam redeploy sozinhas.

## Taxonomia de categorias: dual-write enum↔nó + arestas (2026-10-14)
- **Now**: a categorização virou 2 níveis (grupo→folha, tabela `categories`). O motor
  faz **dual-write ADITIVO**: grava `products.category_id` (folha) ao lado do `category`
  (enum legado). Dicionário/consenso/ingestão já emitem nó (V104–V106). O gráfico "Por
  categoria" é override-aware.
- **OK for dev**: o enum segue como **camada de compatibilidade** — export, WhatsApp e
  `ProductResponse` ainda usam o enum; a folha é extra. Nada quebra se o nó vier null.
- **Before prod / revisitar**:
  1. **Consenso grava nó-GRUPO, não folha fina** — a contagem de votos do consenso é por
     enum; folha-no-consenso exige recontar por nó (re-arquitetura do tally). Esforço: médio.
  2. **Admin (dicionário + aprovar-achado): enum ainda é pick separado** do picker de folha.
     Pra virar pick único, expor `legacy_enum` no `/categories/taxonomy` e derivar o enum da
     folha no FE. Esforço: baixo.
  3. Expor a folha fina no `ProductResponse`/export quando o FE/B2B precisar do nível fino.
- **CI gotcha**: o build do Render roda com **`-DskipTests`** (compila os testes, não roda).
  Falha de runtime de teste NÃO trava deploy → a suíte apodrece sem ninguém ver. **Rodar
  `./mvnw -o test` completo** antes de confiar no verde.

## OOM hardening: flags da JVM + watchdog de heap (2026-09-30)
- **Now**: depois do OOM de prod (2026-09-30 15:09 UTC, serviço ficou meio-morto até
  redeploy manual), o ENTRYPOINT do `Dockerfile` ganhou `-XX:+ExitOnOutOfMemoryError`
  (OOM mata o processo → Render reinicia na hora) e `-XX:MaxRAMPercentage=75.0` (heap
  limitado a 75% da RAM do container; não havia `-Xmx`). (**Atualização:** o `Dockerfile`
  hoje usa `-XX:MaxRAMPercentage=50.0` — a 75% o RSS estourava o container, ver o
  comentário no próprio `Dockerfile`.) Além disso, o
  `HeapPressureMonitor` checa o heap a cada 60s e, acima de 90% por 3 ticks
  consecutivos, loga `heap.pressure` em ERROR e manda UM e-mail de alerta ao admin
  (cooldown 6h). Config em `economizaai.monitoring.*` (`HEAP_ALERT_THRESHOLD`,
  `HEAP_ALERT_CONSECUTIVE_TICKS`, `HEAP_ALERT_COOLDOWN_MINUTES`, `HEAP_CHECK_DELAY_MS`).
- **OK for dev**: alerta vai para a caixa de contato; sem métricas externas ainda.
- **Before prod**: nada a aplicar à mão — o start command vive no `Dockerfile` do repo
  (Render roda `runtime: docker`), então o deploy normal já leva as flags. Quando houver
  Prometheus/Grafana, considerar alertas de heap por métrica em vez de e-mail.
- **Effort**: feito.

## ForceUpdateGate do app ainda lê o gist do polyf (2026-09-30)
- **Now**: o backend já serve `GET /api/v1/app-config` (mesmo shape do gist), mas o app
  DISTRIBUÍDO ainda busca `gist.githubusercontent.com/polyf/…/app-config.json` — conta
  polyf morta, ninguém consegue editar o gist (o kill-switch atual é inoperante).
- **OK for dev**: o gist segue servindo o JSON estático (gate NO-OP); o endpoint novo
  está pronto esperando o release do FE trocar a URL.
- **Atualização (2026-11-10)**: o código do FE já aponta `APP_CONFIG_URL` para
  `${BASE_URL}/app-config` (`src/services/appConfigService.ts`). O que segue em aberto é
  só a base instalada antiga (builds que ainda leem o gist) — manter o gist vivo até lá.
- **Before prod**: publicar o release do app apontando `APP_CONFIG_URL` para
  `https://api.economizaai.app/api/v1/app-config` e SÓ ENTÃO considerar o gist
  descartável — **o gist precisa continuar vivo (não deletar a conta/arquivo) até a base
  instalada migrar**. Depois disso, setar as env vars `APP_CONFIG_*` no Render quando for
  puxar a alavanca. Effort: troca de 1 constante no FE + release.

## RevenueCat webhook persiste provider genérico "revenuecat" (2026-09-30)
- **Now**: o `RevenueCatWebhookService` grava `provider="revenuecat"` fixo em toda
  assinatura de loja — o `RevenueCatWebhookRequest` nem captura o campo `store` do payload
  (APP_STORE/PLAY_STORE). O FE agora roteia o botão "Gerenciar assinatura" pelo provider
  (APPLE/APP_STORE → App Store, GOOGLE/PLAY → Play, desconhecido → **oculto**) — com o
  valor genérico, assinante real de loja não veria o botão.
- **OK for dev**: billing de loja está INERTE (sentinel CHANGEME); nenhum assinante real
  passa pelo webhook ainda.
- **Before prod (ativar billing de loja)**: mapear o campo `store` do RevenueCat para o
  provider persistido (APP_STORE/PLAY_STORE) no webhook antes de credenciar as lojas.
  Effort: campo no DTO + mapeamento + teste, ~1h.

## Sign in with Apple JS (web) — item futuro deliberado (2026-09-30)
- **Now**: login com Apple existe só no app nativo; o web não oferece "Sign in with
  Apple JS". Decisão do owner (2026-09-30): fica para depois, não é gap esquecido.
- **OK for dev**: o web tem login local + Google; Apple é exigência de App Store
  (nativo), não do web.
- **Before prod (quando priorizar)**: integrar Apple JS no FE web + validar o
  `id_token` no endpoint social existente (client-id web próprio no portal Apple).

## Aliases globais criáveis por qualquer usuário (poisoning residual) (2026-09-22)
- **Now**: `POST /products/{id}/aliases` (fluxo de mapear item não reconhecido no app) cria
  um `ProductAlias` **GLOBAL** — a canonicalização de TODAS as households passa a usar o
  mapeamento. O check de conflito impede REMAPEAR descrições já estabelecidas, então o
  ataque se limita a reivindicar descrições novas/raras.
- **OK for dev**: é o design colaborativo (mesmo modelo de confiança das notas alimentando
  o índice); poucos usuários, dano limitado e reversível via admin.
- **Before prod (escala)**: mover alias criado por usuário para um tier de menor confiança
  (household-scoped até ganhar consenso, como o promote-consensus do categorizer) ou exigir
  N households concordando antes de virar global. Efforts: ~1-2 dias.

## Prefetched (PE) segue sem verificação server-side (2026-09-22)
- **Now**: `/receipts/prefetched` foi restringido a UFs bloqueadas (só PE), mas para PE o
  conteúdo continua client-authored — o servidor não consegue verificar (portal bloqueia
  datacenter). Notas PE forjadas ainda entram no índice colaborativo após confirm.
- **OK for dev**: superfície reduzida a 1 UF; exige conta + chave PE válida bem-formada.
- **Before prod (escala)**: dar `ReceiptOrigin` próprio a receipts prefetched e excluí-los
  do índice colaborativo (precedente: PHOTO), ou verificar a chave via serviço de status
  NF-e. Effort: ~1 dia.

## Access token de 24h sem denylist (2026-09-22)
- **Now**: JWT de acesso vale 24h; logout/troca de senha revoga só o refresh — o access
  token segue válido até expirar (stateless, sem denylist).
- **OK for dev**: janela conhecida e o refresh já rotaciona single-use.
- **Before prod (escala)**: encurtar TTL para 15-60 min (exige FE tratando refresh
  proativo — validar no app antes) ou denylist em cache. Effort: config + teste FE.

## Import em massa por chave (RS) — reconsulta server-side, sem throttle (2026-09-22)
- **Now**: `POST /receipts/import[/nfg-csv]` reconsulta cada chave RS direto do nosso IP
  de servidor — NFC-e via `SAT-WEB-NFE-NFC_*.asp`, NF-e 55 via SVRS `ConsultaPublicaDfe`
  (`RsChaveReconsultClient`). Sem backoff/throttle explícito além do limite de concorrência
  do pool `RECEIPT_INGEST_EXECUTOR`.
- **OK for dev**: volume baixo; um usuário importando dezenas de chaves passa tranquilo.
- **Before prod**:
  1. **Rate-limit / bloqueio de IP**: um lote grande (ou muitos usuários) batendo em
     `sefaz.rs.gov.br` pode fazer o SEFAZ throttlar/bloquear nosso IP. Adicionar throttle por
     host + backoff, e/ou mover a reconsulta para **on-device (PE-style, `/receipts/prefetched`)**
     usando o IP residencial do usuário. Bound do tamanho do lote também.
  2. **reCAPTCHA da NF-e 55**: o POST do `ConsultaPublicaDfe` hoje **não exige** o captcha, mas
     ele está na página — se o SEFAZ passar a exigir, o caminho NF-e 55 quebra e precisa de
     solver (pago) ou on-device.
  3. ~~**Só RS**: outras UFs voltam `receipt.import.unsupported`.~~ **Resolvido (2026-11)**:
     o gate de UF do import por chave foi removido (a chave `receipt.import.unsupported` não
     existe mais); toda UF passa pela cadeia by-chave (reconsulta grátis → Infosimples), ver
     CLAUDE.md "Manual-chave submit". Os itens 1 e 2 seguem valendo.
- **Effort**: throttle+backoff pequeno; on-device fallback médio.

## E-commerce price comparison — built but INERT for dev (2026-09-22)
- **Now**: the "vale a pena online?" subsystem is wired end to end but ships **dark**.
  With no provider configured it serves only **admin-CURATED offers** (precision-first):
  an admin maps an EAN → an online product + price + link via
  `POST/PUT/DELETE /admin/ecommerce/offers`, and `GET /receipt-items/{id}/offer` returns
  the cheapest offer compared to what the user paid.
- **Why it's OK for dev**: no external calls until credentials land; curated offers alone
  prove the loop and monetize via affiliate links without needing catalog API access.
- **Turn on a provider (Mercado Livre) — set these on Render (all empty by default):**
  - `ECOMMERCE_ENABLED=true` (master switch)
  - `ECOMMERCE_MERCADOLIVRE_ENABLED=true`
  - `ECOMMERCE_MERCADOLIVRE_CLIENT_ID` / `ECOMMERCE_MERCADOLIVRE_CLIENT_SECRET` — the ML
    **App ID + Secret** (developers.mercadolivre.com.br). OAuth2 client-credentials → token.
  - `ECOMMERCE_MERCADOLIVRE_AFFILIATE_TAG` — your ML affiliate tag (appended to links).
    ⚠️ ML has **no official affiliate API**; the link/commission format must be confirmed
    live. Optional third-party link API: `ECOMMERCE_MERCADOLIVRE_AFFILIATE_API_KEY` +
    `ECOMMERCE_MERCADOLIVRE_AFFILIATE_API_URL` (e.g. Bot do Afiliado's `/convert-links`).
  - Optional: `ECOMMERCE_MERCADOLIVRE_SITE_ID=MLB`, `ECOMMERCE_MERCADOLIVRE_BASE_URL`,
    `ECOMMERCE_WORTH_IT_MIN_SAVINGS=0`, `ECOMMERCE_DEFAULT_CEP`.
- **Verify before trusting a provider**: `MercadoLivreProvider.searchByEan` (OAuth +
  `/sites/{site}/search?q=EAN`) is written to the documented ML shape but **untested live** —
  confirm the token grant, the EAN search, freight, and the affiliate link when creds land.
  It's guarded (returns empty on any error), so a bad config can't break the offer lookup.
- **Extensible**: add a new e-commerce = new `EcommerceProvider` impl + a
  `economizaai.ecommerce.providers.<key>` block. No orchestration changes.

## Garimpo de promoções — built, live search blocked on ML creds (2026-09-22)
- **Now**: admin deal-hunting on top of the e-commerce providers (`/admin/garimpo/**`):
  term search → append-only price history (`garimpo_price_snapshots`, one row per price
  CHANGE) → watches swept hourly (`GARIMPO_WATCH_SWEEP_DELAY_MS`) whose NEW hits POST to
  `GARIMPO_WEBHOOK_URL` (group bot / n8n; empty = log-only). Absorbs and retires the
  standalone `~/Documents/projects/garimpo` prototype.
- **Blocked on the same ML creds as above**: the PUBLIC ML Search API now returns
  **403 forbidden** (verified live 2026-09-22), so `searchByTerm` always authenticates via
  OAuth client-credentials — no creds → localized 503 on `/search` and watch runs. Watches
  CRUD, marketplaces and history work without creds. ⚠️ Confirm when creds land that the
  client-credentials token is accepted by `/sites/MLB/search?q=<term>` — ML has been
  tightening scopes; if it 403s too, the fallback is a user-token (authorization-code) flow.
- **Token caching**: ML tokens are now cached until ~expiry (in-memory, per instance) —
  relevant for the hourly sweep not to burn a token grant per watch.
- **No webhook receiver yet**: the group-bot side (Telegram/WhatsApp) doesn't exist; point
  `GARIMPO_WEBHOOK_URL` at n8n/Zapier or a tiny bot when the ofertas group launches.

## Discount-hunter leaderboard — opt-in (2026-09-22)
- Public "caçador de descontos" ranking (`GET /leaderboard/discount-hunters`) shows only
  households that **opted in** via `PATCH /leaderboard/opt-in` (`share_in_leaderboard`,
  default false). Admin view (`GET /admin/leaderboard/discount-hunters`) shows everyone.
  The metric ("item bought below the community average, product seen by ≥2 households") is a
  reasonable v1 — revisit the definition/weighting once there's more volume.

## LLM layers — activation + follow-ups (2026-07-22)
- **⚠️ Superseded in part (checked 2026-11-10)**: photo extraction no longer depends on
  `OPENAI_API_KEY` — `PhotoReceiptExtractionService` calls `AiGateway` (Anthropic,
  `economizaai.ai.photo-extract-model`, key `ANTHROPIC_API_KEY`). OpenAI is now only the
  optional second provider of the AI layer (`economizaai.openai`, inert until its key is
  set). The bullets below are the original 2026-07-22 text.
- **Activation**: the whole LLM stack (enrichment, auditor, photo extraction) is
  dormant until `OPENAI_API_KEY` is set on Render — workers skip silently, the
  photo endpoint returns a localized 503. Set the key to go live.
- ~~**Sync vision call**~~ **Resolved**: `POST /receipts/items-photo` is async
  (PROCESSING + poll) and since 2026-11-11 runs on its own bounded executor
  (`ASYNC_PHOTO_EXTRACT_THREADS` 3 / `ASYNC_PHOTO_EXTRACT_QUEUE` 30), separate from the admin
  AI sweep lane and from the categorization lane (`ASYNC_AI_CATEGORIZATION_*`). A full queue
  fails the nota with a localized message instead of blocking.
- **Premium-readiness**: `Feature.PHOTO_EXTRACTION` already gates the endpoint
  (dormant while subscription enforcement is off). Enrichment/auditor are
  global env switches, not per-user.

## Meta Ads spend sync — built but GATED/INERT for dev (2026-09-14)
- **Now**: signup attribution (`utm_*`/`clickId`/`referrer`/`landingPath` on the auth
  calls) + the admin acquisition dashboard (`GET /admin/analytics/acquisition`) are live
  and work with zero external config. The **Meta (Facebook/Instagram) Marketing API daily
  spend sync** that fills the dashboard's `adSpend` block is wired but **dormant** — it
  writes nothing until `META_ADS_ENABLED=true`, `META_ADS_TOKEN`, and `META_AD_ACCOUNT_ID`
  are set (optional tuning: `META_ADS_API_VERSION`=v21.0, `META_ADS_SYNC_DAYS`=30,
  `META_ADS_GRAPH_BASE_URL`). The token must be a **Meta Business System User token with
  `ads_read`** for that ad account.
- **Why OK for dev**: with the vars unset the sync never runs and the `meta_ad_spend` table
  stays empty — the dashboard degrades cleanly (`adSpend.configured=false`, `costPerSignup`/
  `costPerActivated` come back `null`). No paid calls, nothing to break.
- **Before prod**: create the System User + long-lived token in Meta Business Manager, grab
  the ad account id (`act_<digits>`), set the three env vars, and confirm a first sync pulls
  spend (dashboard `adSpend.configured` flips to `true`). Until that's wired with real creds
  the cost-per-signup numbers are all null — the dashboard shows organic/channel funnel only.

## Meta Conversions API (server-side signup events) — built but GATED/INERT for dev (2026-09-16)
- **Now**: every new signup (e-mail + Google/Apple) is reported server-side to the Meta
  Conversions API — email SHA-256-hashed, the ad-click passed as `fbc`, deduped with the
  browser Pixel via `event_id`. Survives ad-blockers / iOS ITP and attributes conversions
  to the campaign. Dispatched async (never blocks or fails a registration).
- **Dormant until** `META_CAPI_ENABLED=true`, `META_CAPI_PIXEL_ID` (the Pixel/dataset id,
  `1088687550381259`) and `META_CAPI_TOKEN` are set. The token is a **Conversions API access
  token generated per-Pixel in Events Manager** (Settings → Conversions API → Generate access
  token) — SEPARATE from the ads token (`META_ADS_TOKEN`). Optional: `META_CAPI_API_VERSION`
  (v21.0), `META_CAPI_GRAPH_BASE_URL`, `META_CAPI_TEST_EVENT_CODE` (Events Manager → Test
  Events, to validate without polluting live data).
- **Why OK for dev**: with the vars unset `MetaConversionsService.isConfigured()` is false and
  nothing is sent — zero external calls, nothing to break.
- **Before prod**: generate the CAPI token in Events Manager for Pixel `1088687550381259`, set
  the three env vars, and confirm `CompleteRegistration` events arrive in Events Manager → Test
  Events (use `META_CAPI_TEST_EVENT_CODE` first). Pairs with the landing Pixel (already live)
  and the app SDK scaffold (frontend `META_APP_EVENTS_SETUP.md`).

## Merchant support gate — two scale follow-ups (2026-07-16)
- **Now**: grey-merchant promotion (`PUT /admin/merchants/{cnpj}/support` →
  SUPPORTED) backfills the price index from ALL of the merchant's confirmed
  receipts in one synchronous transaction.
- **Why OK for dev**: a grey merchant has at most a handful of receipts today.
- **Before prod/scale**: page the backfill (or push it to the async pool) once a
  merchant can have hundreds of confirmed receipts — the admin request would
  otherwise hold a long transaction.
- **Also**: when CNAE classification is disabled (`economizaai.merchant.classify.enabled=false`),
  the index gate FAILS OPEN for unclassified merchants (deliberate, so dev
  environments aren't index-starved). Prod must keep classification enabled or
  every unclassified merchant contributes unreviewed. Effort: small.

## Paid-API cost caps — in place, with two known follow-ups (2026-07-09)
- **Now**: `PaidApiGuardService` meters every paid external call — per-user daily caps
  (`economizaai.paid-api.*`: Infosimples 10, captcha 30, photo 10, HTML rescue 5, AI
  categorization 60, Twilio 3), a **daily ceiling per service** (Infosimples R$ 120, captcha
  R$ 30, vision R$ 30, HTML rescue R$ 15, AI categorization R$ 50, Twilio R$ 15 — the single
  global ceiling is now optional and off), an Infosimples circuit breaker per state
  (5 failures/10min → open 5min), and a `paid_api_call` ledger for invoice reconciliation.
  The admin is warned on WhatsApp at 80% / 100% of each ceiling and of each provider's
  prepaid balance. Enforcement toggles via `PAID_API_GUARD_ENABLED` (logging is always on).
  Variables and defaults: `INFRASTRUCTURE.md` → "Environment variables".
- **Follow-up 1 (accuracy)**: captcha is metered **per scrape**, not per solve. A scrape
  can re-solve up to 3× on retry, so the ledger/cap under-counts captcha cost by up to 3×.
  Fine as a coarse abuse guard; tighten to per-solve (meter inside the solver) if captcha
  spend becomes material. (The BA by-chave reconsult already records each solve.)
- **Follow-up 2 (UX)**: the cap is enforced in the **async** ingest, so hitting it produces
  a `FAILED_PARSE` (reason `receipt.paid_api.quota_exceeded`) instead of a fast 4xx at
  submit. Consider a fail-fast check at `POST /receipts` once we're comfortable predicting
  the paid service from the UF up front.

---

## ⚠️ EAN catalog is NOT seeded by a migration — prod DB starts EMPTY (2026-07-04)
- **Now**: `V51__create_ean_catalog.sql` creates the `ean_catalog` table but seeds NOTHING. The LIVE DB (dev-branch service, what real users hit) currently holds **~910k EAN entries** (verified 2026-09-22): **907.5k `OPEN_FOOD_FACTS`** (the full OFF dump was imported, NOT just the BR subset — the old "~32k Brazilian products" note was wrong), **2.5k `CURATED_IMPORT`**, **~720 `LIVE_API`** (the on-demand fallback cache-through, grows on every scan). ~594k have a brand. Imported via a one-off runtime stream through `POST /api/v1/categorizer/ean-catalog/import-off` (OFF CSV dump → gunzip → batches of 500).
- **Why it matters for prod**: a fresh prod DB rebuilds schema from Flyway on an empty volume → the EAN catalog is **empty**, so barcode-scan (`/products/by-ean`) returns catalog-preview misses and category-for-EAN-items silently degrades to dictionary-only. No migration and (until this note) no checklist item = data-loss-on-migrate trap.
- **Fix before prod**: after the first prod deploy, re-run the OFF import against the prod host (ADMIN token). ~40 min streaming. Consider baking a seed migration or a startup importer later.

## Chave OCR needs the NATIVE Tesseract on the host (2026-07-08)
- **Now**: `POST /receipts/chave/photo` OCRs the printed chave via Tess4J, which needs the native Tesseract lib + `eng.traineddata` on the host. The Dockerfile runtime stage installs `tesseract-ocr` (apt), and `TesseractOcrEngine` probes well-known tessdata paths (homebrew/apt), overridable via `OCR_TESSDATA_PATH`. Local dev on macOS: `brew install tesseract`.
- **Why it's OK for dev**: if Tesseract is missing the feature degrades cleanly — the endpoint returns a localized 503 (`receipt.ocr.unavailable`) and everything else is untouched. Free (Apache-2.0), no per-call cost, safe for commercial use.
- **Before prod**: keep the apt install in any new base image; smoke-test the endpoint after image changes (the `ocr.init` log line at first use says whether the native lib loaded). If real-photo accuracy on thermal paper disappoints, consider a preprocessing pass (deskew/contrast) before reaching for a paid vision API.

## Paid external services — cost + toggles (CapSolver, Infosimples) (2026-07-04)
- **CapSolver** (captcha): required for MS + SC receipts. `CAPTCHA_PROVIDER=capsolver` + `CAPTCHA_API_KEY`. Without it, MS/SC receipts 503 ("coming soon"). ~US$1-3 / 1000 solves. The key lives only in the Render env vars of each service (never in the repo).
- **Infosimples** (paid SEFAZ fallback, all UFs): `INFOSIMPLES_ENABLED=false` by default; enable with the API key. **~R$0.24 PER QUERY** — a silent cost sink once on. Fires when the primary scraper exhausts retries on a rescuable failure and when a manually-typed bare chave hits a signature-only state (PR/SP). **CE stopped being Infosimples-only on 2026-10-12**: `CearaNfcePortalAdapter` consults the portal's hidden JSON api (`POST nfce.sefaz.ce.gov.br/nfce/api/notasFiscal/qrcodev3/` with `{chave_acesso, versao_qrcode:3, tipo_ambiente:1}` → `{xml: <DANFE html>, erro}` — reverse-engineered from the Angular shell's own `app.js`; the old "Inconsistência no QR Code" recon error was CLIENT-side JS validation, not a server rejection). Free, no captcha, bare-chave works (v3 needs no QR signature — validated live rendering a July nota, so no early purge either). `infosimples-only-states` default is now EMPTY (knob kept for a future state); Infosimples remains CE's fallback like everywhere else. Caveat: validated from a residential IP — if the portal blocks Render's datacenter IP (like PE does), the fallback picks it up and the fix is device-fetch/proxy, watch `ce.fetch` logs after the first organic CE nota.
- **Account state (verified live 2026-10-01): ACTIVE and enabled in dev AND prod.** Conta pré-paga `xandivieira@outlook.com`, franquia mínima R$100/mês, consumo real ~R$8–11/mês (CE teve 0 notas nos últimos 90 dias em prod — o consumo vem dos fallbacks de outras UFs). The account was dormant Jul–Aug (the 603 "sem saldo" era) and was recharged in September — anything claiming "Infosimples morto/dormant" refers to that window and is outdated.
- **Sweep model under observation (2026-10-01):** the finance ledger (`InfosimplesFinanceService`) assumes the franquia sweeps unused saldo at month close (~06h do dia 1º) — set/2026 closed with `varrido=92.08`, but the LIVE balance on 01/10 (post-06h) still showed **R$91.84**, i.e. the saldo carried over. Either the sweep lands later or the provider changed policy. Watch the next daily snapshots; if the carryover holds, fix `closePreviousMonth` (varrido math) and correct the 2026-09 row.
- **CE outage alerting (2026-10-01):** `InfosimplesAlertService` e-mails the admin on any failed paid consult (6h throttle), on saldo < `INFOSIMPLES_LOW_BALANCE_THRESHOLD` (default R$5) at the daily snapshot, and when the account API is unreachable — a dead account can no longer fail CE silently.
- **Device-fetch genérico (2026-10-12, flag OFF):** `DEVICE_FETCH_GENERIC_ENABLED` (default `false` = comportamento de sempre; só PE device-fetcha, e após o Infosimples). Ligada: (1) qualquer falha de fetch recuperável (IP-block/portal fora) com cliente capaz vira `NEEDS_DEVICE_FETCH` **antes** do Infosimples (`SefazIngestionService.fetch` 3-arg + `PrefetchPolicy.isGenericEnabled`); (2) se o device-fetch falha, `ReceiptIngestionService.ingestPrefetched` continua pro Infosimples (`fetchViaInfosimplesFallback`), menos em nota de contingência (fica CONTINGENCY_PENDING, sem gasto); (3) conteúdo device-fetch de UF fora da allowlist `prefetch-allowed-ufs` grava `receipts.index_eligible=false` → só histórico pessoal, nunca o índice (checado em `PriceIndexService.isContributionBlocked`, mesmo trust-class de PHOTO). `PrefetchPolicy` separa **elegível** (device-fetch, genérico quando ON) de **confiável** (índice, sempre a allowlist). **Correção (Fase 2): o app JÁ é genérico** — `resolveDeviceFetch` (FE) busca a URL do QR on-device pra qualquer estado, sem gate de UF (só o atalho `tryDeviceFetch` upfront é PE-only). O gap real era backend: quando o device-fetch NÃO resolve (portal complexo tipo GO, ou app fechado), o `ProcessingReceiptSweeper` falhava a nota no timeout **sem tentar Infosimples** → com a flag ON, GO regrediria. **Fechado:** `sweepStaleDeviceFetchViaInfosimples` (não-transacional, gated na flag) re-enfileira `NEEDS_DEVICE_FETCH` estagnado → PROCESSING → `ReceiptIngestionService.ingestInfosimplesOnly` (reusa ingestResolved, HTTP untransacted + tx curta). Flag OFF = sweeper falha como sempre; pool rejeitado → markFailed na hora. **Com isso a flag é segura de ligar sem regressão.** Falta só a otimização opcional de generalizar o atalho `tryDeviceFetch` do app. Provado em dev. **Desistência imediata (2026-10-12):** o sweeper (15 min) é só backstop pra app que SUMIU. No caminho normal, quando o device-fetch do app não resolve ele manda `POST /receipts/{id}/device-content {"unavailable": true}` (campo novo; `rawContent` opcional) → `submitDeviceContent` passa `rawContent=null` → `ingestPrefetched(null)` → `fromClientContent` falha no chave ausente → `continueToInfosimples` **na hora** (não espera o sweeper). FE: `resolveDeviceFetch` sempre posta o desfecho e devolve true → `ReceiptsScreen` segue acompanhando (sem falso "falhou"); `DEVICE_FETCH_BUILD='df4'`. Não é flag-gated (desfecho correto da cadeia, vale pro PE também); só o build novo do app dispara.
- **LLM parse-rescue (2026-10-12):** `HtmlReceiptRescueService` sits between the free parser and Infosimples — PARSE failures on already-fetched HTML go to the AI (`AiGateway`/Anthropic extractor model, the app's LIVE stack — NOT the dormant OpenAiClient; needs `ANTHROPIC_API_KEY`, already set dev+prod) before any R$0.24 Infosimples re-fetch. Cost ~R$0.03-0.13/rescue by note size; ledgered as `LLM_HTML_RESCUE` (cap `LLM_HTML_RESCUE_DAILY_CAP:5`/user/day, toggle `LLM_HTML_RESCUE_ENABLED:true`) + tokens in `ai_usage_log` (`RECEIPT_RESCUE`). Validated against the REAL model on all 10 state fixtures (10/10 exact totals/items — `HtmlReceiptRescueRealLlmTest`, gated on `LLM_RESCUE_REAL_TEST=true`, costs money, never in CI). It does NOT help FETCH failures (IP block, captcha wall, portal down) — those still go straight to Infosimples (CE has its own native scraper since 2026-10-12, `CearaNfcePortalAdapter` — see the Infosimples bullet above).
- CapSolver and Infosimples are both listed in `application-prod.yaml`'s header checklist.

---

## Household merge/split — shipped dark, two follow-ups before enabling
- **⚠️ Atualização (2026-11-10)**: o merge **não está mais desligado** — o default de
  `economizaai.households.merge-enabled` é `true` (`HOUSEHOLDS_MERGE_ENABLED:true` no
  `application.yaml`, compartilhamento de domicílio shipado em 2026-11). O texto "gated OFF /
  flag is off" abaixo é histórico. O follow-up da cópia só-de-cabeçalho segue no código
  (`HouseholdMergeService.copyReceiptHeader`, marcador `TODO(prod)`); os demais itens
  precisam ser reconferidos contra o fluxo novo de saída-com-cópia.
- **Now**: full merge (join `bringData` + per-category), restore-on-leave by
  `origin_household_id`, and mutual data-share consent are implemented and tested
  (unit + real-DB integration), but gated OFF by `economizaai.households.merge-enabled`
  (default false). Conflict rule: host household wins; the joiner's colliding row is
  parked on its origin, restorable on split.
- **Why OK for dev**: flag is off, so join/leave behave exactly as before (membership
  only, no data loss thanks to the Phase 0 provenance + delete guard).
- **Before enabling in prod**:
  - **Consent copy is receipt-HEADERS only** — `HouseholdMergeService.copyReceiptHeader`
    does not deep-copy `ReceiptItem`s (TODO(prod) marker in the code). A partner who
    approves sharing gives the requester market/total/date/chave, not line items.
    Decide if that's the intended scope or wire an item deep-copy.
  - **3-way LeaveScope is partially wired**: the `LeaveScope` enum + consent path exist,
    but `leave()` currently restores ORIGINAL_ONLY; `ORIGINAL_PLUS_SHARED` needs a
    per-membership `joined_at` to define the shared window (design Q2, not yet built).
  - Add endpoint-level tests for the consent controller + a join-with-merge flow once
    the flag is turned on.
  - The DataShareConsent controller is LIVE but effectively inert (documented Phase 2
    partial) — consents can be created/answered, yet nothing consumes them while the
    merge flag is off. Intentional; revisit with Phase 2.

---

## SMTP wired to GoDaddy/Titan — deliverability not hardened
- **Now**: auth emails (password-reset code, email verification) send via GoDaddy/Titan
  SMTP (`smtpout.secureserver.net:587`). Creds live in the Render env vars of each service
  (`SMTP_*`). Verified end-to-end (code arrives, reset works).
- **Why OK for dev**: real send works; the FE flow is fully testable.
- **Before prod**:
  - **Deliverability**: test emails landed in **spam (Lixo Eletrônico)**. Add **SPF**,
    **DKIM**, and **DMARC** DNS records for `relyonai.com.br` (Titan provides the values)
    so mail authenticates and stops being junked. ~1 hr + DNS propagation.
  - **Rotate the SMTP password** — it was shared in plaintext over chat during setup.
  - Consider a dedicated transactional provider (SES/SendGrid/Resend) for volume +
    bounce handling if auth-email traffic grows.

---

## Billing: apps ready (RevenueCat), web built (Mercado Pago) — both inert on env vars
- **Now**: two webhook paths feed the same entitlement engine. **RevenueCat** (`POST /webhooks/revenuecat`, Authorization header == `REVENUECAT_WEBHOOK_AUTH`) covers iOS/Android IAP; **Mercado Pago** (`POST /webhooks/mercadopago`, `x-signature` HMAC == `MP_WEBHOOK_SECRET`) covers the web assinatura created by `POST /subscriptions/checkout` (2026-09-29, provider decision: MP). Both **fail closed** when their secret is blank (constant-time compare). The generic `POST /webhooks/subscription` seam and its `BILLING_WEBHOOK_SECRET` were removed on 2026-11-11 (unused). A scheduled `SubscriptionExpiryService` downgrades lapsed PRO hourly (3-day grace for a paid, still-renewing subscription); a provider cancellation keeps access until the period end; Mercado Pago monthly charges advance the period — see MONETIZATION.md "Implementation status".
- **Why OK for dev**: dev grants PRO via the admin set-tier endpoint (`PUT /api/v1/admin/users/{id}/subscription-tier`); checkout returns a localized 503 until credentialed. No payment is collected until the env vars below land.
- **Before charging (owner setup, all env-var only):**
  - **Apps:** RevenueCat account + store products, then fill `REVENUECAT_WEBHOOK_AUTH` (created on both services as `CHANGEME`, treated as unset) and the FE build env keys `EXPO_PUBLIC_RC_IOS_KEY` / `EXPO_PUBLIC_RC_ANDROID_KEY`. The FE paywall is PRE-WIRED and invisible (2026-09-29): `src/services/iap.ts` lazy-loads `react-native-purchases` (guarded — no crash on Expo Go/web), configures `app_user_id` = user UUID, and PremiumScreen shows the native buy/restore buttons only when the platform key is present. Native module → needs a store build, not OTA. Owner can gather NOW from existing accounts: App Store Connect Paid Apps agreement + subscription product + In-App Purchase Key (.p8 + Issuer/Key ID); Play Console subscription product + service-account JSON (Setup → API access); RevenueCat project wiring both → the two SDK keys + webhook Authorization value.
  - **Web:** MP account → `MP_ACCESS_TOKEN` + `MP_WEBHOOK_SECRET` (+ optionally `MP_PLAN_AMOUNT`, default 9.90) and point the MP webhook at `/api/v1/webhooks/mercadopago`. **Verify the x-signature manifest against a real sandbox event before trusting it** (implemented from docs: `id:<data.id>;request-id:<x-request-id>;ts:<ts>;`, id lowercased). FE needs a "Assinar Premium" screen opening `checkoutUrl`.

## Relevance filter rollout — first telemetry CONSUMER (2026-06-12, in SHADOW)

The first thing that *reads* `notification_events`: a user's own DISMISSED/MUTED
signals suppress deals from their screen + digest (`DealFeedbackService` plugged
into `DealsService`). Rules: MUTED product → hidden everywhere for 180d;
DISMISSED → hidden for 14d (the (product, market) pair when the event carries
`marketCnpj`, the whole product when it doesn't). Per-user scope — one member's
dismissal never hides a deal from the rest of the household.

**Rollout + validation protocol (`RELEVANCE_MODE`, default `SHADOW`):**
1. **SHADOW (now)**: filter computed + logged (`relevance.shadow suppressed=N`),
   UX unchanged. Let it run while real users dismiss/mute things.
2. **Check the report**: `GET /api/v1/admin/notifications/relevance-report?days=30`.
   The decision metric is **regret** — taps/list-adds/conversions on a product
   the same user had recently dismissed/muted, i.e. engagement the filter would
   have prevented. `regretSavings` is the R$ of conversions it would have blocked.
3. **Flip ON** when regret ≈ 0 with meaningful signal volume (`suppression.signals`
   double digits+). Record the pre-flip report numbers.
4. **Compare after 2–4 weeks**: `dismissalRate` should fall, `tapThroughRate`
   hold or rise, `conversions`/`attributedSavings` not fall. Worse → flip back to
   SHADOW (one env var, no data loss — everything derives from the event log).

The report works identically in SHADOW and ON (computed purely from
`notification_events`), so before/after windows are directly comparable.

## Notification telemetry: foundation (Phase A) — first consumer shipped 2026-06-12 (see "Relevance filter rollout" above)
- **Now**: `POST /api/v1/notifications/events` + `NotificationEventService` write a `notification_events` row per user-feedback signal (push opened, deal viewed/tapped, added-to-list, dismissed/muted; plus server-only SENT/DELIVERED/CONVERTED for later). The `deal_surface_state` table (dedup-by-state-change for a future digest) is created but **unread/unwritten**. This is the **telemetry foundation for a future ranking/learning engine** — events are logged now even though no code reads them yet.
- **`metadata` is a JSON string in a TEXT column** (not jsonb) to stay H2-test-portable. If we ever need to query *inside* it (e.g. filter by `discountFraction`), migrate to `jsonb`.
- **LGPD**: `notification_events` is per-user behavioral data on the user's own account. The DB FK is `ON DELETE CASCADE` (account delete reaps it), but it is **NOT yet in `UserService.exportData`** — fold it into the LGPD data-export when that's extended (see the entry above). Flagged.
- **Before prod / before the learning phase**: the producers are now wired (Phase B screen, Phase C digest emits SENT, Phase D attribution emits CONVERTED — see below). The **consumer** that ranks/learns on this data is still TODO; revisit jsonb + export.
- **Telemetry loop status: COMPLETE (producer side).** Phase A (events + state tables) → B (deals screen) → C (digest emits SENT + upserts surface state) → D (savings attribution emits CONVERTED + realized R$). The north-star — **R$ saved attributable to notifications** — is now produced end-to-end and readable at `GET /users/me/savings`. Remaining: the ranking/learning consumer.

## Deals screen ("Ofertas pra você") — Phase B of the notifications overhaul (2026-06-09)
- **Update (2026-11-11)**: a deal's price is now the chain's MOST RECENT observed price (at most `DEALS_MAX_PRICE_AGE_DAYS`, 60) instead of the chain median; the chain of the household's last purchase is never suggested; `priceSource` labels own-purchase vs community evidence. The text below predates that.
- **Now**: `GET /api/v1/deals` + `DealsService` compute the household's currently-relevant discounts on demand. For each product the household bought, it calls `PriceIndexService.bestMarkets` (k-anon + radius + watched logic reused as-is) and keeps the cheapest market that beats last-paid by the progressive `RelevanceThreshold.requiredDropFraction` bar (extracted out of `NotificationRuleEngine` so the engine and the deals screen share one definition). Ranked by savings desc, tie-broken by purchase frequency.
- **Query cost**: one history query for the household, then one `bestMarkets` lookup per distinct product bought (each a couple of small queries). On-demand single-user, so acceptable — but it's an **N-product fan-out** that will want batching once a household buys hundreds of distinct products or once the digest (Phase C) computes this for many users at once.
- **Phase C (digest) reuses this**: the same deal computation feeds the future digest push, which will dedup against `deal_surface_state` (created in Phase A, still unread) so it only re-pushes a deal when the discount/price meaningfully changes. Savings **attribution** (did the user actually buy it cheaper after we surfaced it?) is also a later phase.
- **Telemetry**: this endpoint emits none by design — the app self-reports `SCREEN_OPENED` / `DEAL_VIEWED` / `DEAL_TAPPED` via the Phase A events API.

## Daily deals digest — Phase C of the notifications overhaul (2026-06-09)
- **Now**: `DealsDigestScheduler` runs hourly (cron `0 0 * * * *`, America/Sao_Paulo). Per due user it calls `DealsService`, filters to **newsworthy** deals via `deal_surface_state` (created in Phase A, now read+written), and — if ≥1 — sends ONE `DEALS_DIGEST` push, upserts the state rows, and records a `SENT` `notification_events` row per surfaced deal. The hour each user is "due" comes from `DigestScheduleService` (override → modal shopping-hour−1h with ≥5 issued-at receipts → a per-user default spread over 11:00–19:00) and, since 2026-11-11, is STORED on the user (`digest_effective_send_hour`, V160): due users are selected in SQL, in pages, with "hour reached" instead of "hour equal" (a late run still delivers), and a run with nothing newsworthy does not use up the day's slot. Preferences live on `users` (`digest_frequency`, `digest_send_hour`) and are edited via `GET/PUT /api/v1/users/me/digest-preferences`.
- **Newsworthy rule**: no prior state row (new) OR discount improved by ≥ +0.05 (5 p.p.) / crossed a stricter `RelevanceThreshold` step OR last surfaced before the collaborative lookback window. Standing unchanged deals are skipped.
- **1/day cap mechanism**: a `last_digest_sent_at TIMESTAMPTZ` column on `users` (chosen over scanning `notification_events`) — same-calendar-day (Sao Paulo) ⇒ skip. Hard cap, checked before computing deals.
- **Timezone assumption (v1)**: everything ("now", the due-hour math, the 1/day day boundary) is **America/Sao_Paulo** for all users. **Per-user timezone is a later refinement** — store the user's TZ and resolve "now"/send-hour per user. ~half a day once we capture TZ.
- **WEEKLY day**: WEEKLY digests only fire on **Thursday** (`DealsDigestScheduler.WEEKLY_DAY`). Arbitrary sensible default; make it user-configurable later if needed.
- **O(users × products) fan-out**: each due user triggers `DealsService`, which fans out one `bestMarkets` lookup per distinct product bought. For the hourly batch that's O(due-users × products). We keep it cheap by only processing the small slice of users actually due that hour and isolating each in its own `REQUIRES_NEW` tx (one user's failure is logged and skipped, never aborts the run). **Before scale**: batch/cache the deal computation across users (shared product→market index per run) and/or precompute deal candidates off the write path. Flagged.
- **`DEALS_DIGEST` is SYSTEM-scope** (server-emitted, not a user-toggleable rule) — governed purely by `digest_frequency`, so it is intentionally NOT auto-seeded as a default `NotificationRule`.

## Savings attribution — Phase D of the notifications overhaul (2026-06-09)
- **Now**: `SavingsAttributionService.attribute(receipt)` runs from `ReceiptService.confirm`, **after** the receipt is CONFIRMED + observations recorded, wrapped in a try/catch (`attributeSavings`) so it can **NEVER** break a confirm — any failure is logged (`attribution.failed`) and swallowed (best-effort analytics). For each non-excluded, product-linked item it looks for a `deal_surface_state` row for the (product, market=`cnpjEmitente`) of **any user in the buyer's household** where `converted_at IS NULL` and `last_surfaced_at` is within the **attribution window**. A match records a `CONVERTED` `notification_events` row + stamps `converted_at` so one surfacing is attributed at most once (a re-surface clears it). Surfaces the total via `GET /users/me/savings` (`SavingsService`).
- **Attribution window**: `economizaai.attribution.window-days` (default **14**), a dedicated property in `CollaborativeProperties.Attribution` (distinct from the 90-day collaborative lookback — attribution is about "did they buy *soon after* we nudged", a tighter window).
- **Savings formula** (counted only when **positive**): `(previousLastPaid − paidUnitPrice) × quantity`. `previousLastPaid` = the household's last paid unit price for that product on a confirmed receipt **issued strictly before** this one (the very receipt being attributed never counts as its own baseline — `findHouseholdHistoryForProduct` filtered to `issuedAt < receipt.issuedAt`). If there's **no prior purchase**, we fall back to the surfaced deal's `last_unit_price` baseline, but only when it's higher than paid; otherwise there's no provable savings and we skip.
- **Approximations / honesty**: attribution is **heuristic + correlational, not proven causation** — we credit a deal whenever the user bought within the window after we surfaced it, with no proof the nudge caused it. `previousLastPaid` uses the household's own last unit price (no pack-size/quantity normalization beyond per-unit), and the "no prior purchase" surface-baseline fallback is a community-median proxy, not what *they* used to pay. Good enough to track a directional north-star; not an accounting figure.
- **User-resolution choice**: a digest could go to **any** household member, so we attribute against the surface rows of **every user in the buyer's household** (`UserRepository.findAllByHouseholdId`), not only the buyer — but the `CONVERTED` event itself is recorded against `receipt.getUser()` (the actual buyer). Savings read-back (`GET /users/me/savings`) re-aggregates across the whole household, so per-user vs per-buyer attribution doesn't change the household total.
- **Storage**: realized savings live in a dedicated `notification_events.savings_amount NUMERIC(12,2)` column (added so the north-star sums in plain SQL) **and** are echoed into the JSON `metadata` for completeness. Migration `V44__savings_attribution.sql` adds that column + `deal_surface_state.converted_at`.
- **This completes the notifications-overhaul telemetry loop** (A→D). See the Phase A entry above — the producer side is done; the ranking/learning consumer is the remaining work.

## Write-path is now explicit-alerts-only; PROMO_*/CHEAPER_MARKET toggles are vestigial (2026-06-09)
- **Now**: `NotificationRuleEngine.evaluate` (on receipt confirm) fires **only** the user's explicit `PRICE_DROP` alerts. The discovery defaults (`PROMO_PERSONAL` / `PROMO_COMMUNITY` / `CHEAPER_MARKET`) that used to fire in real time were removed from the write path — that function now lives entirely in the daily deals digest (`DealsService` / `DealsDigestScheduler`). The dead repo methods (`findActiveDefaultRuleOwnersWhoBought` both overloads + `ProductRuleOwner`; `findLastPaidHistoryForProductByHouseholds` + `HouseholdProductPrice`) were deleted with them.
- **Resolved for two of the three (2026-11-11)**: `PROMO_COMMUNITY` and `CHEAPER_MARKET` were RETIRED — no longer seeded, listed or accepted as a preference (the enum values stay only so old rows deserialize); the digest frequency is the control.
- **Still vestigial**: the `PROMO_PERSONAL` default toggle is still seeded and listed, but nothing in the write path reads it. Decide whether the digest should respect it or retire it too.

---

## Social login (Google/Apple): audience check skipped until client IDs are set
- **Now**: `POST /api/v1/auth/google` and `/auth/apple` verify the provider token's signature (RS256 via the provider JWKS), issuer, and expiry. The **audience (`aud`) check only runs when `GOOGLE_OAUTH_CLIENT_IDS` / `APPLE_OAUTH_CLIENT_IDS` are configured**; when empty (dev default) it's skipped with a WARN.
- **Why OK for dev**: signature+issuer+expiry still prove the token is a genuine, current Google/Apple token. Fine for local testing.
- **Before prod**: set both env vars (comma-separated client IDs the mobile app may present as `aud`) so a token minted for a *different* app can't be replayed against us. Google: the OAuth client IDs per platform (Web/iOS/Android) from Google Cloud Console. Apple: the app bundle ID (native) and/or Service ID (web). Team ID + Sign-in-with-Apple key are NOT needed for this identity-token flow (only for the auth-code/refresh flow). JWKS are fetched + cached 1h in-memory (`CachingJwksKeySource`).

## Notification channels: SMS + WhatsApp via Twilio (env-gated); Alexa still a stub; email off by default
- **Now**: the channel framework supports `PUSH` (Expo → FCM/APNs, working), `EMAIL` (SMTP via `EmailDispatcher`, gated by `NOTIFICATIONS_EMAIL_ENABLED`, off by default until SMTP creds are set), `SMS` + `WHATSAPP` (Twilio's Messages API via `TwilioMessageClient`, off by default), and `ALEXA` which remains **structure-only** (`AlexaDispatcher` extends `StubChannelDispatcher` — logs and records a "not implemented" failure on the audit row).
- **SMS/WhatsApp are now implemented** (`SmsDispatcher`/`WhatsAppDispatcher`): they deliver only when Twilio is configured **AND** the target user has a `phone_verified` phone. Otherwise they degrade gracefully — record a `twilio_not_configured` / `phone_not_verified` failure on the notification audit row, never throw. The phone OTP flow (`PATCH /users/me/phone`, `POST /users/me/phone/verify`) sends the 6-digit OTP over SMS via Twilio; when Twilio is unconfigured (dev) it falls back to a `[DEV-MODE] phone OTP for {maskedPhone} = {code}` WARN log so dev can still verify.
- **Env vars** (all blank/off by default): `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_SMS` (SMS sender, E.164), `TWILIO_FROM_WHATSAPP` (Twilio WhatsApp sender number, no `whatsapp:` prefix). `isConfigured(whatsApp)` is true only when sid + token + the relevant From are all set. Bound in `application.yaml` under `economizaai.notifications.twilio`.
- **Swappable**: `TwilioMessageClient` is the only seam — swap it (or its `post()` method) for another SMS/WhatsApp provider without touching the dispatchers.
- **Why OK for dev**: graceful degradation — an unconfigured/unverified channel just produces a failed audit row, never an exception. Push is the primary channel and works end-to-end.
- **Before prod**: set the `TWILIO_*` env vars + register a Twilio WhatsApp sender; implement `deliver()` for the remaining Alexa stub (Proactive Events, skill grant per user); flip `EMAIL` on once Render has SMTP env vars.

## Community-default notifications: backfill + owners-query batched — MOSTLY RESOLVED (2026-06-09)
- **Fixed**: a startup `NotificationDefaultsBackfill` (`ApplicationRunner`) now proactively materializes missing default rule rows for **all existing users** (idempotent bulk `INSERT...SELECT`, one query per default type), so community notifications no longer wait for the user to open settings. (Lazy seeding on `GET /notification-rules` + absent-as-enabled still apply as a fallback.) Also `NotificationRuleEngine` community defaults now run **2 queries per receipt** (one `IN` query per type over all products) instead of 2·P — the per-product `findActiveDefaultRuleOwnersWhoBought` N+1.
- **Also fixed (2026-06-09)**: the per-candidate last-paid lookup in `fireCheaperMarket` is now batched — one `findLastPaidHistoryForProductByHouseholds(productId, householdIds)` query per product builds a `Map<householdId, lastPaid>`, replacing the former one-query-per-rule. So the community write path is now bounded (no per-rule N+1).
- **Still open (minor)**: the 24h cooldown is per default-rule (coarse, one community ping/type/day/user) — revisit if users want per-product granularity.

---

## Merchant segment classification depends on BrasilAPI (best-effort)
- **Now (built)**: `MarketLocation.segment` (UNKNOWN/SUPERMARKET/PHARMACY/OTHER) is verified from the CNPJ's **CNAE** via BrasilAPI (`/api/cnpj/v1/{cnpj}`, free, no auth) — `CnpjActivityClient` + `MarketLocationService.classifyPendingSegments()` (scheduled batch, decoupled from confirm, attempt-capped, name-pattern fallback in `MerchantClassifier`). When a market resolves to PHARMACY, OTHER products bought there are backfilled to the HEALTH category. **Best-effort by design**: a lookup failure leaves the segment UNKNOWN and categorization proceeds normally on the name guess (or just the dictionary) — never blocks ingestion.
- **Why OK for dev**: external dependency is fully optional (toggle `MERCHANT_CLASSIFY_ENABLED=false`), retried, and degrades gracefully. The name fallback covers the big chains immediately, so the CNAE layer is pure accuracy upside.
- **Watch before prod**: BrasilAPI has no SLA / rate limits we control — if it gets flaky at volume, consider (a) a paid CNPJ provider or (b) caching/import a CNAE dataset. Also the first receipt from a brand-new oddly-named pharmacy categorizes on the name guess until the async batch resolves the segment (then the backfill corrects it). Headless/cron envs without outbound internet will just leave segments UNKNOWN.
- `MERCHANT`-sourced categories are excluded from ML training (`TRUSTED_SOURCES`) and locked against admin recategorization downgrades — intentional, keep it that way.

---

## Captcha solver — seam ready, dormant (MS wired, 2026-06-12)
- **Now**: captcha-gated states have a full ingestion path that's switched OFF until a solver provider is configured. `MsDfePortalAdapter` claims MS (`www.dfe.ms.gov.br`, reCAPTCHA v2): GET consult page → detect captcha + extract sitekey → `CaptchaSolver.solveRecaptchaV2` → resubmit → parse with the shared `ResponsiveDanfeParser`. With the default `provider=none` (`NoopCaptchaSolver`), an MS receipt fails fast with **503 `receipt.captcha.unavailable`** ("coming soon"), not a confusing parse error.
- **MS is ON via CapSolver**: `CAPTCHA_PROVIDER=capsolver` + `CAPTCHA_API_KEY=<key>` on the server (verified live 2026-07-02). `TwoCaptchaSolver` also exists as an alternative (`CAPTCHA_PROVIDER=twocaptcha`; `CAPTCHA_BASE_URL` applies only to it — CapSolver's URL is fixed). A provider with a different HTTP API = one new class implementing `CaptchaSolver` with `@ConditionalOnProperty(...havingValue="<name>")`; nothing else changes.
- **Post-captcha layout VERIFIED (2026-06-12)**: a real MS DANFE (C.VALE/Caarapó, saved from a browser after the user solved the captcha) parses correctly — `RealMsFixtureTest` locks all 9 items + R$61,79 total. MS uses `span.txtTit` (not `txtTit2`), 7-digit internal codes (→ ean null), single IBPT total (→ tax null); all handled by the shared parser. So the only thing still **VERIFY ON FIRST REAL SOLVE** is the HTTP transport that exchanges a solved token for the DANFE: `MsDfePortalAdapter.consultUrl` params + `fetchAuthorizedDanfe`'s resubmit shape (isolated for a quick fix — the page is reached via a form POST from the captcha page; capture it in DevTools Network during the first real solve). Cost when live: ~US$1-3 / 1000 solves.
- **Other captcha states**: each has its own portal, so they need their own adapter (reusing `CaptchaSolver` + `ResponsiveDanfeParser`); `economizaai.ingestion.sefaz.captcha.states` lists which UFs the MS adapter claims (MS only for now).

## Experimental all-states fallback chain — LIVE (2026-07-16), learning on demand
- **Now**: every UF without a dedicated adapter is gap-filled with `GenericQrPortalAdapter`:
  GET the QR's own portal URL (SSRF-bounded to `gov.br` hosts) → shared `ResponsiveDanfeParser`
  → Infosimples rescue on fetch OR parse failure (when enabled). All 27 UFs accepted at submit.
  Kill-switch: `SEFAZ_EXPERIMENTAL_ENABLED=false` restores the old fail-fast 400.
- **Learning loop**: every layer attempt on an experimental UF is a `state_ingestion_attempts`
  row (strategy, outcome, qr_host, evidence). `GET /admin/state-coverage` is the per-UF map.
  Admin inbox gets: (a) first-ever success per UF ("capture fixture, promote to svrs.states"),
  (b) total chain failure with chave + QR URL + portal snippet, deduped 1/UF/day.
- **Promotion path**: when a UF succeeds via QR_PORTAL and a real user confirms the items,
  add it to `economizaai.ingestion.sefaz.svrs.states` + its host to `allowed-url-hosts` — it
  becomes VERIFIED (dedicated adapter, no experimental telemetry).
- **Regression watch (verified states)**: we can't stop a supported portal changing its DANFE
  format, but we detect it. Every VERIFIED-state PARSE failure is recorded (strategy
  `VERIFIED_ADAPTER`); when `regression.min-failures` (default 5) parse-failures hit the same
  UF within `regression.window-hours` (default 6h), the admin gets ONE email/UF/day with the
  raw HTML + chave + parser reason — enough to fix the adapter or write a new one. Tuned to
  ignore single bad-scan/aged-out noise; raise the threshold if a low-traffic state false-alarms.
  Honest limit: it can't perfectly tell "format changed" from "burst of bad scans", so it's a
  threshold heuristic, not a proof — but a real format change fails EVERY scan, so it trips fast.
- **Captcha walls get ONE best-effort solve** (reCAPTCHA v2 / Turnstile; token resubmitted
  as a query param — a guess). Charged per solved token (~R$0.03) even when the portal
  rejects it, hence a single attempt, then Infosimples. Failure evidence (captcha type,
  sitekey, page snippet, HTTP status) rides the telemetry + admin email.
- **MG needs captcha + JSF postback** (`portalsped.fazenda.mg.gov.br`): PrimeFaces/ViewState
  portal, reCAPTCHA, DANFE delivered via an AJAX postback — needs a real post-solve fixture
  (DevTools capture) before an adapter can be built without guessing component IDs. Infosimples
  covers it meanwhile. **Update:** a dedicated `MgNfcePortalAdapter` (+ `MgNfceDanfeParser`)
  now exists in `service/sefaz`, as do `DfNfcePortalAdapter` and `GoiasNfcePortalAdapter`.

### JS-anti-bot-walled portals (RJ / F5 BIG-IP) — parser-ready, needs an unlocker fetch
- **Problem**: some SEFAZ portals sit behind a client-side **JavaScript anti-bot challenge**.
  Our fetch layer is a plain `RestClient` GET with **no JS engine**, so it never gets past the
  wall to the DANFE. Confirmed case: **RJ** (`consultadfe.fazenda.rj.gov.br`) runs **F5 BIG-IP
  ASM** — fingerprinted by the `TS0…`/`TSPD` + `BIGipServer…` cookies it sets and the obfuscated
  JS it serves instead of the receipt. A real browser solves the challenge invisibly (verified —
  the user opened the URL and saw the full receipt); our server gets only the challenge page.
  (MG's `ERR_CONNECTION_RESET` may be a related network-level bot defense; unconfirmed.)
- **NOT a parser problem**: RJ renders the SAME responsive-DANFE layout the shared parser
  already handles — proven by `RealRioDeJaneiroFixtureTest` on a real captured fixture (market,
  R$65,00 total, item all extracted). So once something gets past the wall, `ResponsiveDanfeParser`
  works UNCHANGED — zero parsing work to onboard RJ (and likely other F5/Imperva/Cloudflare states).
- **Works TODAY via Infosimples** (`INFOSIMPLES_ENABLED=true`): the paid by-chave fallback
  handles RJ fine (~R$0.24/nota). So nothing is broken — this note is about making it FREE-ish.
- **Solution options** (to move JS-walled states off the paid fallback):
  1. **Web-unlocker API** (RECOMMENDED — Bright Data Unlocker / ZenRows / ScrapingBee / Scrapfly).
     Send the URL, they run the JS challenge + rotating proxies server-side, return the HTML.
     Fits our stack perfectly: it's one HTTP call, drops in as a chain layer exactly where
     Infosimples sits, metered through `PaidApiGuardService`. ~R$0.008–0.015/nota (≈US$1.5/1k) —
     roughly **30× cheaper than Infosimples**, and covers ALL JS-walled vendors (F5/Imperva/
     Cloudflare/DataDome), not just RJ. Downside: a new paid vendor with a ~US$50–70/mo floor
     (or pay-as-you-go, no floor but higher per-call).
  2. **Self-hosted headless browser** (Playwright/Chromium): runs the challenge in-house.
     REJECTED for now — Chromium needs ~300–500 MB + system libs on top of the JVM's ~1 GB;
     tight on the Render Standard 2 GB box, big image, slow cold start, fragile.
  3. **Reverse-engineer the F5 TS-cookie generator in Java**: brittle, high maintenance (F5
     rotates the challenge JS). REJECTED.
- **Recommendation / when to build**: DON'T build yet — Infosimples covers RJ today. Add the
  unlocker layer when `/admin/state-coverage` shows JS-walled states crossing ~1–2k notas/month
  (that's roughly the break-even vs a US$69/mo plan at ~R$0.23 saved/nota). Build = one
  `UnlockerFetchClient` wired before Infosimples for the affected UFs, metered like the other
  paid layers. Parser side is already done + fixture-guarded.
- Refs: [Scrapfly F5 bypass](https://scrapfly.io/bypass/f5), [ZenRows F5 (2026)](https://www.zenrows.com/blog/bypass-f5),
  [Bright Data Web Unlocker pricing](https://brightdata.com/pricing/web-unlocker).
- **Watch before prod scale**: captcha-walled portals (AC, RN, TO…) that reject the guessed
  resubmit burn one solve + one Infosimples query per nota — if such a state gets popular,
  build its adapter (the telemetry tells you which).

## Multi-state SEFAZ coverage = 2 / 27 verified (PR added 2026-06-12; MS ready, needs solver)
- **⚠️ Stale snapshot (checked 2026-11-10)**: this entry describes 2026-06. Today
  `svrs.states` defaults to `RS,PR,SP`, there are dedicated adapters for MS, SC, CE, DF, GO
  and MG, and every other UF goes through the experimental all-states chain (entry above —
  all 27 UFs accepted at submit while `SEFAZ_EXPERIMENTAL_ENABLED` is on). Live per-UF truth:
  `GET /admin/state-coverage`.
- **Now**: RS + **PR** have working end-to-end ingestion. PR has its own portal
  (`www.fazenda.pr.gov.br/nfce/qrcode`) but renders the SAME responsive-DANFE
  layout as SVRS, so the shared adapter parses it unchanged — verified with a
  real RaiaDrogasil/Curitiba fixture (`fixtures/sefaz/pr/`). The QR carries the
  full portal URL; bare-chave fallback picks the portal by the chave's UF.
  PR caveat learned: drugstores print merchant-internal item codes (6-7 digits)
  in the EAN slot → codes < 8 digits are no longer stored as EANs (description
  matching takes over). Other UFs still return `UnsupportedStateException`.
- **What we know**: empirical probe of all 27 portals on 2026-05-06 documented in `docs/MULTI_STATE_RECON.md`. ~10 UFs are server-rendered + simple GET (Tier 1 — quick to add once we have real chaves). 3 UFs use JSF/ASP.NET ViewState (Tier 2, more code). 5 UFs gate behind captcha (Tier 4, needs 2Captcha or similar). 1-2 are SPAs requiring a headless browser.
- **Bottleneck**: not code, **data**. Adapters can't be written safely against synthetic chaves; each UF needs 1 real, recent (≤ 3 months) cupom HTML to develop selectors against. See `docs/MULTI_STATE_RECON.md` for the population coverage analysis (Tier 1 + Tier 2 = ~54% of Brazil) and recommended implementation order.
- **Fix when ready**: collect chaves via e-CAC, state cashback portals, or contacts in each state; save HTML in `src/test/resources/fixtures/sefaz/<uf>/`; implement adapter following `SvrsSharedPortalAdapter` template.

---

## Storage / infrastructure

### Profile picture storage — RESOLVED: Cloudflare R2 in prod (2026-10-09)
- **Prod/dev now**: `CloudflareR2ProfilePictureStorage` (S3-compatible, RestClient + hand-rolled SigV4 — no AWS SDK). Selected via `PROFILE_PICTURE_STORAGE=r2`. Buckets `economizai-profile-pics` (prod) / `-dev` (dev); creds in `R2_*` env vars. Bytes still served via `GET /users/me/profile-picture` (API unchanged). The 7 existing pics were migrated disk→R2 by `DiskToR2ProfilePictureMigration`.
- **Why**: the local-disk persistent volume PREVENTED Render zero-downtime deploys (a disk attaches to only one instance, so the old instance must stop before the new boots → ~34-60s of 5xx on every deploy — this is what got iOS 1.3.1 rejected). Removing the disk (after R2 migration) + the readiness health check gave **true zero-downtime** (verified 37×200 / 0×5xx during a deploy).
- **Dev default stays local**: `LocalDiskProfilePictureStorage` (`@ConditionalOnProperty ... matchIfMissing=true`) → `PROFILE_PICTURE_DIR` when `storage` is unset. See [[project_r2_profile_pics]].

### Push notifications = Expo Push Service (works in dev with no setup)
- **Now**: `PushDispatcher` calls the Expo Push HTTP API (`https://exp.host/--/api/v2/push/send`). The FE (React Native + Expo) registers an Expo Push Token via `PUT /api/v1/users/me/push-token`; the backend POSTs to Expo, which routes to FCM (Android) or APNs (iOS).
- **Why this stack and not firebase-admin**: the FE generates Expo tokens (`ExponentPushToken[...]`), which can't be sent through raw FCM. Expo also removes the need for service-account JSON, native config and SDK init.
- **Dev**: works out of the box — no env var, no project setup. Push reaches the device via Expo Go.
- **Prod (optional)**: set `EXPO_ACCESS_TOKEN` env var with a token from https://expo.dev → Account → Access Tokens. Raises rate limits and feeds the Expo analytics dashboard. Without it, sends still work but at lower throughput.
- **Prod (iOS)**: publishing to the App Store requires an Apple Developer Program membership ($99/year — Apple's fee, not Expo's) and uploading the APNs auth key to Expo (Expo handles the rest of the iOS push plumbing).

### SMTP email = disabled (impacts notifications + auth flows)
- **Now**: two paths consume SMTP:
  1. `EmailDispatcher` (notification channel) — `@ConditionalOnProperty(NOTIFICATIONS_EMAIL_ENABLED=true)`, defaults off, falls back to PUSH/NONE when off.
  2. `AuthEmailSender` (password reset + email verification) — always loaded; if SMTP isn't configured, **logs the link with `[DEV-MODE]` prefix** instead of sending. The reset/verify endpoints still return 204, so the FE flow works in dev — the developer copies the token from server logs.
- **Why OK for dev**: no SMTP creds, FE end-to-end testing still works (manually grab the link).
- **Why NOT OK for prod**: real users won't see a `[DEV-MODE]` log line. They get NO password-reset / verification email at all.
- **Fix before prod**: set SMTP creds in env (`SMTP_HOST/PORT/USERNAME/PASSWORD`) and flip `NOTIFICATIONS_EMAIL_ENABLED=true`. Recommend SES, Mailgun, or Postmark — Gmail SMTP rate-limits hard. ~30 min.

---

## Hosting / access — history

- **Today**: the app runs on Render (dev + prod) — `INFRASTRUCTURE.md` is the source of truth.
  Local development is still `docker compose up -d db` + `./mvnw spring-boot:run`
  (Postgres on :5432, app on :8080; defaults mirror `application.yaml`, no `.env` needed;
  `docker compose down -v` wipes the local volume).
- **History**: between 2026-06-03 (Render's free Postgres was suspended) and 2026-09-22 the dev
  server was a self-hosted Windows box (full stack in Docker, Cloudflare quick-tunnel,
  self-hosted CI runner, PowerShell watchdogs). It was retired on 2026-09-22 and its scripts
  and tunnel proxy were removed from the repo on 2026-11-11.
- **Gotcha worth keeping**: `postgres:18` changed its data-dir convention — mount the volume at
  `/var/lib/postgresql` (the parent), not `/var/lib/postgresql/data`, or `initdb` refuses to run
  (already right in `docker-compose.yml`).

---

## Data correctness

### ML categorization gated OFF (dictionary-only for now)
- **Now**: `economizaai.ml.category-apply-enabled=false`. The cascade applies dictionary entries only; the ML model is confidently wrong at current data volume (~hundreds of products), so its predictions aren't written. It's still trained + measured (shadow) via `/categorizer/benchmark`.
- **Why OK for dev**: dictionary-only is deterministic and currently 100% on the golden set; uncategorized is better than confidently-wrong.
- **Fix / revisit**: once `mlCategoryAccuracyPct` (shadow) is consistently high — after the catalog has thousands of trusted labels — flip `ML_CATEGORY_APPLY_ENABLED=true` and watch the benchmark. Track via `/categorizer/quality/history`.

### User corrections are global (should be per-household) — see HELP.md "Planned"
- **Now**: `PATCH /products/{id}` mutates the shared canonical product, but it IS
  **ADMIN-gated** in `SecurityConfig` (the interim fix is in place). Since 2026-11-11 the
  user-facing "corrigir item" (`POST /receipts/{id}/items/{itemId}/correction`) is
  household-scoped too: it writes a friendly name + category override for that household and
  queues the global change as a `PRODUCT_CORRECTION` finding for the admin.
- **Fix before real multi-user volume**: corrections-as-votes (design in HELP.md) so repeated
  household corrections graduate to the global product without an admin click.

### Cross-household chave dedup breaks if a receipt ever has no chave
- **Now**: the same-chave double-count guard is solid for the only ingestion path we have (QR scan → 44-digit chave always present). Same household, same chave: CONFIRMED → 409, non-final → replaced. Different households, same chave: the second household keeps its own personal receipt, but `PriceIndexService.recordContributions` skips the community write (`existsContributionForChaveFromOtherHousehold`) and logs `price_index.write.skipped reason=duplicate_chave_other_household`. This is exactly the desired behavior.
- **The gap**: that guard is wrapped in `if (receipt.getChaveAcesso() != null)`. A receipt ingested **without** a chave would bypass dedup entirely and could double-count in the community index. No such path exists today.
- **Revisit when** we add any non-QR ingestion (manual 44-digit entry, photo/OCR fallback — backlog #21, or manual item entry). At that point either require a chave before a receipt can contribute to the community index, or add a content-hash fallback dedup (market CNPJ + issuedAt + item set) for chave-less receipts.

### Receipt discounts: tracked and reported alongside gross spend, never distributed
- **Now**: we stopped distributing the receipt-level discount across item prices (the old `reconcileItemsToTotal` proportional rateio — deleted). Item `unitPrice`/`totalPrice` are kept gross-as-printed so the collaborative price index records the real shelf price. The "Descontos R$" line is parsed into `receipts.discount_total` (V46) and exposed as `discountTotal` on receipt responses.
- **Consumed by insights/dashboard (gross + discount, FE nets it)**: spend totals stay gross; the discount is reported alongside so the FE computes net (`total − discount`). `SpendInsightsResponse.totalDiscount` + per-bucket `discount` on byMonth/byWeek/byMarket; `InsightsQueryResponse.summary.totalDiscount` + per-bucket `discount` for receipt-level groupings (DAY/WEEK/MONTH/YEAR/MARKET/CHAIN); `SpendSnapshot.discount` on the dashboard.
- **No per-bucket discount for CATEGORY/PRODUCT**: a receipt-level discount can't be attributed to one category/product without rateio (out of scope). Those buckets carry `discount=null`; only the period-level `totalDiscount` is provided. Per-item discount attribution is impossible on SVRS anyway (the portal only gives the receipt-level total).
- **Implementation note**: discount aggregations query `Receipt` directly (no item join) so `discountTotal` is counted once per receipt; `/insights/query` uses a DISTINCT-(receiptId, …) select so the item-join doesn't multiply it.
- **Why OK for dev**: the price index (what matters most) is honest, spend breakdowns stay internally consistent (gross everywhere), and the FE can show "gastou R$ X · R$ Y em descontos". Still no use of the discount beyond reporting (e.g. a future "markets with the biggest discounts").

---

## Merchant marketing accounts — payment is INERT (2026-10-06)

- **Now**: every approved merchant claim opens a `merchant_subscriptions` row with
  status `PROMO`, free until `MERCHANT_FREE_UNTIL` (2026-12-31). There is NO payment
  integration: after the promo window those chains stop passing
  `MerchantSubscriptionService.requirePublishing` (promo writes → 402) until an admin
  manually sets status `ACTIVE` in the DB.
- **Fix before charging merchants**: wire Mercado Pago (reuse the consumer
  subscription webhook pattern) onto `merchant_subscriptions` + an admin endpoint to
  flip status. Also decide dunning (EXPIRED sweep job).
- Consumer-facing sponsored feed stays dark until `MERCHANT_PROMOS_FEED_ENABLED=true` —
  flipping it on is a product decision, not a code task (needs FE "Patrocinado" section).

## Monitoring / ops

### ~~`/actuator/prometheus` is public (no auth)~~ — RESOLVED (2026-06-11)
- **Fixed**: `/actuator/prometheus` now sits behind a dedicated HTTP Basic security chain (`SecurityConfig.metricsSecurityFilterChain`, `@Order(1)`), with the credential from `economizaai.metrics.username/password` (`METRICS_USERNAME`/`METRICS_PASSWORD`). **Fail-closed**: blank password ⇒ no user ⇒ every request 401, so it's never exposed unauthenticated. `/actuator/health` stays public on the main chain for UptimeRobot.
- **Remaining (infra, not code)**: nothing scrapes it yet — stand up Prometheus + Grafana (hosted) and set `METRICS_PASSWORD`.

### Logs go to stdout only — no central aggregation
- **Now**: the app logs to stdout and Render's log stream is the only place to read them (limited retention, no cross-service search).
- **Fix before serious ops**: ship logs to BetterStack, Loki, or Papertrail (a Render log drain). ~1 hr.

### Read caches (dashboard / insights) are in-process
- **Now**: `dashboard` (2 min) + `insightsSpend` (5 min) are cached in-memory via Caffeine (`CachingConfig`), keyed by household with a per-household generation counter (`HouseholdCacheGen`) bumped on receipt mutations. ETags via `ShallowEtagHeaderFilter`.
- **Why OK for dev**: single instance, so one in-process cache is the whole truth; generation counter resets on restart (at worst one recompute).
- **Why NOT OK at scale**: multi-instance would give each node its own cache + its own generation map → a mutation on node A wouldn't invalidate node B's cache (stale reads up to TTL). The ETag filter is shallow (still computes the body), so no compute savings either.
- **Fix before multi-instance**: move the cache + generation counter to a shared store (Redis). ~half a day. Single instance is fine until then.

---

## Last-checked: 2026-11-11

When you take care of an item above, **delete it from this file** instead
of marking it done — keep the file lean so what remains is what's
actually outstanding.
