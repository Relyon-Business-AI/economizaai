# Onboarding em massa — como encher o histórico do usuário no 1º acesso

> Não confundir com o `ONBOARDING.md` da raiz (aquele é setup de **dev**). Este
> doc é sobre **importar muitas notas de uma vez** para um usuário novo começar
> com histórico, em vez de escanear uma-a-uma.
>
> Status: **v1 construída (RS) — 2026-09-22.** Import por chaves/CSV da NFG com
> reconsulta server-side de NFC-e 65 (SAT-WEB) e NF-e 55 (SVRS). Detalhes na
> seção "Implementado" abaixo. Scan em lote e e-CPF seguem planejados.

## Implementado (2026-09-22)

**Endpoints** (autenticado, household-scoped):
- `POST /api/v1/receipts/import` — body `{ "chaves": ["...", ...] }` (máx. 500).
- `POST /api/v1/receipts/import/nfg-csv` — multipart `file` = CSV cru da NFG; as
  chaves são extraídas server-side (aceita o formato de dois blocos com espaço).
- Ambos devolvem `202` com `ReceiptImportResponse` = `{ received, queued,
  queuedReceiptIds[], rejected, rejectedChaves[{chave, reason, reasonMessage}] }`.
  O FE faz poll de cada `receiptId` em `GET /receipts/{id}` (mesmo fluxo do scan).

**Fluxo:** valida (44 díg + DV) → só RS reconsultável (modelo 55/65) → filtra
CNPJ já bloqueado + duplicados do domicílio → cria receipt `PROCESSING` → após
commit dispara `ingestReconsult` (async). A reconsulta busca o HTML no portal
público, raspa os itens e reusa **todo** o pipeline (merchant gate, EAN, persist,
parse-failure guarda o HTML). Ineligíveis voltam em `rejectedChaves` com motivo
localizado (`receipt.import.*`).

**Componentes:** `ReceiptImportService` (orquestra + parseia CSV),
`RsChaveReconsultService`/`RsChaveReconsultClient` (HTTP RS por chave),
`SatWebNfceParser` (NFC-e 65) e `SvrsNfeProdutosParser` (NF-e 55, com EAN real).
Fixtures reais (CPF mascarado) em `src/test/resources/fixtures/sefaz/rs/`.

**Limites/riscos:** só RS por ora (outras UFs → `receipt.import.unsupported`); o
"Código" do NFC-e é PLU interno, não EAN (NF-e 55 traz EAN real); reconsulta
server-side em lote pode ser **rate-limited/bloqueada por IP** pelo SEFAZ (o pool
async limita a concorrência) — fallback futuro é on-device PE-style; o POST da
NF-e 55 tem reCAPTCHA na página mas hoje não é exigido (pode endurecer sob volume).
Segmento e-commerce (Amazon) cai em GREY: entra no histórico pessoal, fora do
índice colaborativo — comportamento seguro por padrão.

## O problema

Hoje uma nota entra sempre **individualmente** (QR scan, foto do QR, foto do
cupom via LLM, chave manual, ou `POST /receipts/prefetched`). Todas passam pelo
mesmo `ReceiptIngestionService`. Um usuário novo começa vazio e só ganha valor
depois de escanear várias — atrito alto no onboarding.

Queremos duas coisas:

1. **Reduzir o atrito do scan** (enfileirar e passar a câmera em lote).
2. **Import em massa não-manual** — a pessoa baixa/encaminha algo e o histórico
   aparece. De quebra, é a ponte natural para o histórico de **e-commerce**.

## A realidade brasileira do dado fiscal (pesquisado 2026-09-22)

O sonho "baixa um arquivo com tudo e sobe" esbarra em COMO o dado é distribuído.
Os **itens** (linha a linha) só existem em dois lugares: no **XML completo** da
nota, ou reconsultando a **chave** no portal da UF.

| Fonte que o usuário consegue obter | Tem itens? | Cobre qual UF | Barreira |
|---|---|---|---|
| Export do programa estadual (Nota Fiscal Gaúcha/Paulista/etc.) | ❌ só cabeçalho (chave, emitente, total, data) | a do programa | itens exigem reconsulta por chave |
| Reconsulta por chave (nossa, `dfe-portal.svrs`) | ✅ | SP/PR/CE (Infosimples); **RS não** | pago; NFC-e RS exige a assinatura do QR neste endpoint |
| **Reconsulta RS por chave pura (`sefaz.rs.gov.br/NFE/NFE-NFC.aspx`)** | ✅ **tem itens** | RS (NFC-e 65 **e** NF-e 55) | **público, sem login** — ver achado validado abaixo |
| e-CAC / "Consulta DF-e" (federal, por CPF) | lista chaves; XML completo limitado | todas | exige gov.br autenticado ou e-CPF |
| **XML da NFC-e / NF-e** (o arquivo em si) | ✅ **tem tudo** | qualquer UF | ver abaixo |

### Achado validado (2026-09-22): RS reconsulta por chave pura, público

Colando uma chave da CSV da NFG em `https://www.sefaz.rs.gov.br/NFE/NFE-NFC.aspx?chaveNfe=<44>`
o portal RS renderiza a **nota inteira com itens** (Código, Descrição, Qtde, Vl
Unit, Vl Total) — testado com NFC-e 65 (Zaffari) **em janela anônima/deslogado**,
funciona. Isso vale para NFC-e 65 e NF-e 55 emitidas no RS.

- **Derruba o bloqueio antigo** ("RS não reconsulta por chave") — aquele vale só
  para o endpoint que a ingestão atual usa (`dfe-portal.svrs`, que exige os
  parâmetros assinados do QR). Este endpoint legado aceita a **chave pura**.
- **Mecanismo:** ASP.NET WebForms — GET serve o form, o botão **Avançar** faz um
  **postback** (ViewState + cookie de sessão) que devolve a nota. É o padrão
  "Tier-2 stateful" do `MULTI_STATE_RECON.md`. Um `GET ?chaveNfe=` cru do servidor
  devolve só a casca do portal; reproduzir exige o fluxo de 2 passos.
- **Ressalva:** a coluna "Código" é o **código interno do lojista** (PLU), não
  necessariamente EAN de 13 dígitos.
- **Consequência:** para RS, **CSV da NFG → extrai chaves → reconsulta → raspa
  itens** é viável e gratuito (destrava o item 3 abaixo para RS). Implementação:
  adapter server-side Tier-2 (reusa o scraping; risco de rate-limit/bloqueio de IP
  em lote) **ou** on-device PE-style (o webview do usuário renderiza; robusto
  contra bloqueio de IP). Ainda **não** reproduzido server-side — é trabalho de
  adapter, não incógnita de viabilidade.

Sobre o XML:

- **NF-e modelo 55** (compra online, B2C/B2B): o comprador em geral **recebe o XML
  por e-mail**; download por CPF também funciona no **Portal Nacional**
  (`nfe.fazenda.gov.br`) com gov.br/certificado. É o caso do e-commerce.
- **NFC-e modelo 65** (cupom de mercado): **não** é centralizado. O Portal
  Nacional só serve NF-e 55. NFC-e depende do portal estadual — a maioria só
  deixa *consultar*, e o download do XML costuma exigir **certificado** e existir
  só em algumas UFs. Fragmentado.
- **Novidade 2026:** SEFAZ-SP lançou o **SAE** (Sistema de Apoio à Escrituração
  da NFC-e, Nota Técnica 2026) para consultar/recuperar chaves e XMLs — vale
  revisitar o reconsult de SP quando formos mexer nisso.

**Conclusão:** não existe "um arquivo nacional com os itens de todas as notas do
CPF". O artefato que carrega os itens E é agnóstico de estado é o **XML**.

## Roadmap sugerido (ordem de payoff)

### 1. Scan em lote (batch scan) — sem custo, funciona onde já temos adapter

**Intenção:** o usuário abre um modo "importar várias", **enfileira** as notas e
passa a câmera continuamente — cada QR é decodificado **on-device** e postado ao
pipeline atual, sem parar entre uma e outra. Alternativa/soma: selecionar
**várias fotos da galeria** de uma vez.

- Reusa 100% do backend. FE decodifica (ou usa `POST /receipts/photo`) e dispara
  N submits; o app já trata `PENDING_CONFIRMATION` e o sweeper de PROCESSING.
- Backend: no máximo um endpoint de conveniência que aceite uma **lista** de
  QR/chaves e enfileire cada um como submit individual (nunca uma transação só —
  cada nota é uma unidade de trabalho async, ver convenções de transação no
  `CLAUDE.md`). Validar cap por request (anti-abuso) e devolver um id por nota
  para o FE acompanhar o progresso.
- Ganho: elimina o atrito do "uma-a-uma" sem depender de nada externo. É o único
  item deste roadmap 100% sob nosso controle.

### 2. Import de XML (a melhor aposta de import em massa) — agnóstico de estado

**Intenção:** o usuário sobe **arquivos XML** (avulso ou **lote/zip**) e nós
parseamos os itens **direto do XML**, sem SEFAZ, sem captcha, para **qualquer
UF**. Fonte natural dos XMLs: o e-mail da compra (NF-e 55 do e-commerce), ou os
estados que permitem baixar NFC-e.

- Parser de XML (schema NF-e/NFC-e — `infNFe`, `det`/`prod`) reaproveitando o
  mesmo mapeamento item→canonical do fluxo atual; o HTML scraping vira só uma das
  entradas.
- **Ponte com e-commerce:** compra online → NF-e 55 por e-mail → usuário sobe →
  itens no histórico. Habilita comparação "online vs mercado" (ver
  [`ECOMMERCE_COMPARISON.md`](./ECOMMERCE_COMPARISON.md)) sem depender do backfill
  por CPF.
- **Funil de menor atrito — "email-in":** um endereço para o qual o usuário
  **encaminha** o e-mail da nota; extraímos o XML anexo e ingerimos. Zero fricção
  de upload.
- Cuidado LGPD: XML traz CPF/CNPJ — aplicar o mesmo sweep de anonimização antes
  de persistir (ver invariantes no `CLAUDE.md`).

### 3. Import do export estadual (CSV/Excel/PDF) — DESTRAVADO para RS

O export é **só cabeçalho** (chave, emitente, total, data — sem itens), então os
itens dependem de reconsulta por chave. **Novidade (2026-09-22):** o achado
validado acima mostra que **RS reconsulta por chave pura e é público** — logo o
fluxo **CSV da NFG → extrai chaves → reconsulta `NFE-NFC.aspx` → raspa itens** é
viável e gratuito para RS (NFC-e 65 de mercado/farmácia **e** NF-e 55 de
e-commerce). Isso reverte o antigo "RS rende zero itens".

- **Filtro:** classificar por `TipoDoc.`/modelo (55 vs 65) e por segmento (CNAE do
  CNPJ na chave) — só ingerir supermercado/farmácia + e-commerce; descartar
  restaurante/posto/etc. (segmentos não suportados).
- **Deduplicação:** cruzar com receipts já existentes por chave antes de reingerir.
- **Fora do RS:** ainda depende do portal da UF (SP/PR/CE via Infosimples pago;
  demais conforme cobertura). Revisitar SAE-SP (2026) para SP.
- **Implementação:** ver as duas rotas no achado validado (adapter server-side
  Tier-2 vs on-device PE-style).

### 4. Auto-import por e-CPF / gov.br (pago, algum dia) — máxima automação

**Intenção:** com o **e-CPF** (certificado A1/A3) ou uma **sessão gov.br
autenticada do titular**, puxar automaticamente a **lista DF-e do CPF + os XMLs**
do e-CAC / portais estaduais — "as compras aparecem sozinhas".

- **Viável só com credencial do titular** — nunca com o CPF sozinho (spike
  2026-07-07). É **polling**, não push, e **fragmentado por estado**.
- Implica **guardar/operar credencial sensível do usuário** (certificado ou
  sessão) — peso jurídico e de segurança alto; provável feature **paga**.
- Fica no registro como o "endgame" de automação; **não priorizar** até termos
  volume e um caso de negócio que pague a complexidade + risco.

## Recomendação

Com o achado validado (RS reconsulta por chave pura, público), a ordem muda:

- **(3) para RS agora é a maior alavanca** — a base atual é majoritariamente RS, e
  o CSV da NFG já entrega dezenas de chaves por usuário. Import da CSV → reconsulta
  → itens popula mercado **e** e-commerce no primeiro acesso, de graça. Provar
  primeiro o adapter Tier-2 `NFE-NFC.aspx` (ou on-device) com 1 chave server-side.
- **(2) import de XML + email-in** segue como a aposta agnóstica de estado e a
  ponte de e-commerce para usuários fora do RS.
- **(1) scan em lote** é o quick win de atrito, 100% sob nosso controle.
- **(4) e-CPF/gov.br** guardado como endgame pago.

## Ver também

- Spikes de origem: bullets em `HELP.md` (Suggested Additions).
- Cobertura por estado e como coletar chaves reais: [`MULTI_STATE_RECON.md`](./MULTI_STATE_RECON.md)
  (o e-CAC "Consulta DF-e" já é usado lá para coletar chaves).
- Comparação e-commerce vs mercado: [`ECOMMERCE_COMPARISON.md`](./ECOMMERCE_COMPARISON.md).
