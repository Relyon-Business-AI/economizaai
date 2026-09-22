# Feature: Auto-import de notas por CPF ("CPF na nota" → aparece sozinho no app)

**Status:** SPIKE (2026-07-07/08). Três desenhos avaliados:
1. **Auto-import recorrente (polling)** — ❌ inviável (login gov.br tem captcha+MFA a cada
   consulta; OAuth gov.br só dá identidade; API NF-e oficial é e-CNPJ/paga/empresa).
2. **Premium via e-CPF (A1)** — candidato de nicho: `NFeDistribuicaoDFe` é automatizável, mas
   o usuário precisa comprar um e-CPF (~R$120-250/ano) → adoção baixa; descartado pelo custo.
3. **⭐ Backfill de onboarding via webview** — ✅ **VIÁVEL** (spike RS OK, 2026-07-08): usuário
   loga 1× no webview (ele vence o captcha), e a API JSON `BuscaNotasNfg` devolve as notas por
   CPF/período com a chave de cada uma. Zero custo pro usuário. **É o próximo candidato real.**

**Scan do QR permanece o fluxo principal.**
**Origem:** ideia de tester — em vez de escanear o QR, o usuário pede "CPF na nota"
no caixa e as compras apareceriam automaticamente no app.

---

## A ideia

Hoje o fluxo é: usuário escaneia o QR da NFC-e → a gente busca/parseia. A proposta é
eliminar o scan: como toda NFC-e emitida com CPF fica registrada na SEFAZ, bastaria
"puxar por CPF" e importar tudo sozinho. Desde **nov/2025 a NFC-e é exclusiva para
CPF** (vendas a CNPJ viraram NF-e modelo 55), então o dado por-CPF é ainda mais central.

## Como o dado realmente é acessível

Vários estados têm **"Consulta NFC-e por CPF"**: o consumidor faz um cadastro no portal
da SEFAZ, recebe uma senha por e-mail e loga com **CPF + senha** (ou **certificado
digital / e-CPF**). Aí vê a **lista completa de todas as notas emitidas no nome dele**.
O dado existe e é puxável por CPF — mas **sempre atrás de login do próprio titular**.

## Resultado da spike (2026-07-07)

Testado contra a API do Infosimples (que já integramos) e o catálogo SEFAZ:

- O endpoint **NFC-e do Infosimples é só POR CHAVE** (param `nfce` = os 44 dígitos).
  **Não existe** "manda CPF → recebe a lista de notas".
- Chamada da NFC-e unificada com `cpf=01216437009` (sem chave) → `code 600` (erro).
- A listagem "Notas Recebidas" (ex.: Prefeitura SP) exige **`pkcs12_cert` +
  `pkcs12_pass`** — ou seja, **certificado digital (e-CPF A1)** do titular.
- Portais estaduais de "consulta por CPF" (ex.: AM) exigem **cadastro + senha** do CPF.

**Conclusão dura:** com **apenas o CPF, não se recupera nada** — seria um buraco de
privacidade que (corretamente) não existe. Todo caminho exige a **credencial autenticada
do titular**: senha do portal estadual OU certificado e-CPF. Isso é o mesmo muro gov.br
que já batemos no RS, agora como mecanismo *primário*.

## Spike RS ao vivo + checagem de API gov.br (2026-07-07)

Testado o RS de ponta a ponta com um CPF real:

- O login do **Nota Fiscal Gaúcha** (`nfg.sefaz.rs.gov.br`) **migrou pro gov.br SSO** —
  não há mais form de CPF+senha próprio, só um botão `/govbr-redirect.aspx`.
- O redirect cai em `sso.acesso.gov.br/login` com **captcha (reCAPTCHA) + CPF + token
  (MFA)**. O titular **teve que logar manualmente** — confirmando que é **inautomatizável**
  (captcha e 2FA existem exatamente pra barrar bot). Sessão fica no navegador do usuário.

**O gov.br oferece alguma API?** Checado — **não para este caso**:

- **Login Único (OAuth/OIDC)**: scopes só de identidade (`openid`, `profile`, `email`,
  `phone`, `govbr_confiabilidades`). Prova **quem** é o usuário; **não** expõe notas.
  Não existe scope "ler minhas NFC-e".
- **API oficial de dados de NF-e** (SERPRO/Receita, via Loja Serpro / Conecta gov.br):
  existe, mas exige **e-CNPJ (certificado)**, é **contratada/paga**, voltada a **empresas/
  órgãos**, e é **NF-e** (modelo 55) — **não** a lista NFC-e-por-CPF do consumidor.
- **Conecta gov.br** é barramento **governo-a-governo** (convênios), não aberto a app privado.

**Conclusão:** o único caminho programático que resta é o **certificado e-CPF (A1)**
autenticando como o cidadão — que quase nenhum consumidor tem. Portanto o auto-import por
CPF **não é viável como feature escalável de consumidor** pelos canais oficiais. O **scan
do QR permanece o caminho**. Revisitar só se o gov.br publicar um scope OAuth de documentos
fiscais do cidadão.

## ⭐ Backfill de onboarding via webview — SPIKE RS OK (2026-07-08)

Variante mais promissora: **importar o histórico UMA VEZ no onboarding**, com o
usuário logando ele mesmo (o captcha/MFA do gov.br deixa de ser problema nosso —
é um passo único que o humano vence). Spike real feito no RS (Playwright abrindo
o Chrome do usuário, ele logou via gov.br, capturamos a sessão):

**Resultado: VIÁVEL.** O portal RS Pessoa Física expõe uma **API JSON interna limpa**:
```
GET https://www.sefaz.rs.gov.br/portal/Consultas/BuscaNotasNfg?dtIniPeriodo=YYYY/MM/DD&dtFimPeriodo=YYYY/MM/DD
```
retornando um array com, por nota: `nomeContrib` (emitente), `nomeMunicipio`,
`dtEmiDoctoFormatada`, `nroDocto`, **`tipoDoctoOrig`** (55=NF-e, 65=NFC-e),
**`chaAceNfeDocto`** (chave de acesso, 44 díg. com um espaço a remover), `vlrDocto`,
`nomeNatOperDocto`. Além disso a tela tem **export CSV/Excel/PDF** (fallback robusto).

**Desenho da feature:**
1. Onboarding → webview abre o portal do estado; usuário loga via gov.br (1×, captcha/MFA).
2. Na sessão autenticada, chamamos `BuscaNotasNfg` (ou export CSV) → lista das notas.
3. Importamos por **chave** (resumo: loja/data/valor; opcionalmente item-a-item depois).
4. Usuário já começa com histórico.

**Confirmado / a confirmar:**
- ✅ Mecanismo funciona: JSON por CPF, por período, com a chave de cada nota.
- ✅ Login = gov.br + **hCaptcha**; OAuth só identidade (o usuário vence o captcha uma vez).
- ⚠️ No teste, todas as notas eram **NF-e 55 (Amazon/e-commerce)** — o usuário deu CPF em
  compras online, não no mercado. O campo `tipoDoctoOrig` suporta **65 (NFC-e)** e é a
  mesma lista, mas **falta ver um CPF com NFC-e de mercado** pra confirmar 65.
- ⚠️ Profundidade: a chamada veio com range ~60 dias — validar se aceita período maior.

**Caveats gerais:** é **por estado** (isto é o portal do RS; SP/etc. têm endpoints
próprios → integração 1 a 1); depende da **sessão do webview** (curta); **LGPD**
(dado do próprio usuário, com consentimento → ok) + ToS do portal; e só aparecem notas
**onde o CPF foi informado** (a premissa do produto — usuário passa a pedir CPF na nota).

**Custo:** zero pro usuário (sem e-CPF, sem Infosimples) — só nosso esforço de dev por estado.

**Efeito colateral valioso:** a lista por CPF traz também as **NF-e 55 (e-commerce, ex.:
Amazon)**, não só NFC-e de mercado. Esse dado abre uma feature futura: **comparar preço por
item e-commerce vs. mercado físico** e sugerir onde compensa comprar (ver HELP.md → Suggested
Additions, "E-commerce vs. mercado").

**Veredito:** é o **caminho preferido** para "começar com histórico". Recomendo protótipo
real de 1 estado (RS já mapeado) + testar um CPF que tenha NFC-e de mercado.

## Como PREMIUM (setup único) — o desenho que realmente fecha

O usuário topou o setup. Então o critério vira "setup **uma vez**, depois roda sozinho":

- **e-CPF (A1) + `NFeDistribuicaoDFe`** — usuário sobe o certificado uma vez; a gente
  consome o web service oficial da SEFAZ como pessoa física e puxa os DFe/notas dele.
  Automatizável, oficial, sem captcha. **Este é o candidato premium.**
  - **Barreira de adoção:** o usuário precisa **comprar um e-CPF** (~R$120-250/ano, numa
    autoridade certificadora) — poucos consumidores têm. Feature de nicho, não de massa.
  - **Liability:** guardar o `.pfx` (chave privada = identidade digital legal do usuário)
    exige cofre de segredos dedicado + consentimento LGPD explícito + escopo mínimo.
  - **A confirmar num spike real:** se o DistribuicaoDFe retorna **NFC-e (65)** por CPF
    (é sólido pra NF-e 55; pra NFC-e de consumidor precisa validar com um certificado real).
- **gov.br senha** — descartado: captcha+MFA a cada consulta, não é "setup único".

## Desenhos viáveis (todos opt-in, com consentimento LGPD)

- **Path A — senha do portal estadual:** o usuário cadastra o CPF no portal da SEFAZ do
  estado dele e nos dá CPF + senha; a gente (ou o Infosimples, se construírem o endpoint
  "por CPF") faz **polling periódico** (ex.: 1×/dia) e importa as notas novas.
  - Prós: sem scan. Contras: **fragmentado por estado** (cada SEFAZ é diferente; alguns
    atrás de gov.br com MFA/captcha), guardar senha de terceiro é sensível.
- **Path B — certificado e-CPF (A1 pkcs12):** usuário sobe o certificado; autenticamos e
  listamos as notas. Tecnicamente o mais limpo, mas **poucos consumidores têm e-CPF**
  (custa ~R$100-200/ano, é coisa de PJ/contador).

**Comum aos dois:** é **polling, não push** (a SEFAZ não notifica apps de terceiros) →
"aparece em até ~1 dia", não em tempo real. E **LGPD**: guardar credencial/certificado
do usuário é o maior risco do desenho — precisa de cofre de segredos + consentimento
explícito + escopo mínimo.

## Custo

- Via Infosimples: pago por consulta (~R$0,28 hoje na NFC-e por-chave). Um endpoint
  "por CPF" (se existir) seria pago por consulta também → polling diário × usuários = custo
  recorrente a dimensionar.

## Recomendação

- **Não substitui o scan** — mantém o QR como padrão universal (funciona em todo estado,
  sem credencial). O auto-import por CPF vira um **upgrade opt-in** onde for viável.
- **Começar por 1 estado** com portal CPF+senha simples (não-gov.br) OU exigir e-CPF, e
  medir custo/confiabilidade num piloto.

## Próximo passo para continuar a spike

O spike travou por falta de credencial — **um CPF sozinho não basta**. Para prosseguir eu
preciso de **um** destes, para um CPF de teste:
1. **Senha do portal "consulta por CPF"** da SEFAZ de um estado (o titular cadastra e
   passa CPF + senha), ou
2. Um **certificado digital e-CPF A1** (arquivo `.pfx`/`.p12` + senha).

Com isso eu testo a listagem real por CPF de ponta a ponta e digo se dá pra construir.

## Sources

- SEFAZ/AM — NFC-e pode ser consultada pelo CPF: https://www.sefaz.am.gov.br/noticias/9164
- Infosimples — SEFAZ/NFC-e (só por chave): https://infosimples.com/consultas/sefaz-nfce/
- Infosimples — Notas Recebidas SP exige certificado (issue #583):
  https://github.com/infosimples/infosimples/issues/583
- Gov.br/ITI — consultar NF-e online: https://www.gov.br/iti/pt-br/assuntos/noticias/iti-na-midia/como-consultar-nota-fiscal-eletronica-online
