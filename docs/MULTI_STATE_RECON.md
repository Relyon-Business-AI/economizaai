# Multi-state SEFAZ recon — empirical map of NFC-e portals

Captured 2026-05-06. This document is a snapshot of what we learned by
probing every state's NFC-e consultation portal directly. Use it to plan
which adapter to write next, what risks each state carries, and what the
path-to-coverage looks like.

> **⚠️ Coverage has advanced a lot since this recon (updated 2026-09-22).** The
> "1 UF verified (RS)" figures below are the ORIGINAL snapshot — outdated. Current
> reality (from the code/config): **RS, PR, SP verified** via `SvrsSharedPortalAdapter`;
> **MG** (`MgNfcePortalAdapter`), **SC** (`SantaCatarinaNfcePortalAdapter`) and **GO**
> (`GoiasNfcePortalAdapter`) have dedicated adapters; **MS/TO** use captcha-gated
> adapters; **CE** runs via the Infosimples fallback; everything else degrades to the
> experimental QR-portal + Infosimples chain with per-UF telemetry.
> **The live source of truth for coverage is `GET /admin/state-coverage`** (VERIFIED vs
> EXPERIMENTAL + per-layer success/failure). Keep the **tier classification and
> per-portal risk notes below** — those are still the useful guide for the next state;
> just treat the "verified count" as historical.

## TL;DR

- **End-to-end ingestion verified for 1 UF**: **RS**, via the SVRS shared
  portal (`dfe-portal.svrs.rs.gov.br`). Backed by `SvrsSharedPortalAdapter`
  + real Zaffari/Bistek HTML fixtures + passing tests.
- **0 other states verified end-to-end yet** — every other UF needs a
  fresh, real chave to write a parser against. Synthetic chaves we used
  here returned error pages, which tell us about the portal's layout but
  not about the success-path HTML our parser must read.
- **Cobertura potencial sem captcha/SPA: ~14 estados** (Tier 1 + Tier 2
  below). Cobertura de 100% precisa enfrentar captcha (5 estados) ou
  rodar SPA (1-2 estados).

## Method

Probed each portal with one fake-CNPJ chave for that UF (placeholder CNPJ
`12345678000195`, sequential numbers). Captured: HTTP status, redirects,
final URL, content type, response size, and key markers in the body.

The synthetic chaves do not return real cupom data — they exist only to
exercise URL routing and show what the portal does for an unknown chave.

For RS, the probe used a fake chave against the SVRS portal, which returned
an inline error message — confirming SVRS is server-rendered (matches our
working production adapter).

## Empirical results (2026-05-06)

### Tier 1 — server-rendered, simple GET, plausibly easy

These portals returned plain HTML with the chave/error message inline.
Writing an adapter is mostly a matter of finding the right Jsoup selectors.

| UF | Portal | Notes |
|---|---|---|
| **AL** | `nfce.sefaz.al.gov.br/QRCode/consultarNFCe.jsp` | 5KB, server-rendered, "Erro" hint inline |
| **AP** | `sefaz.ap.gov.br/nfce/nfcep.php` | 136 bytes — minimal, almost certainly a single-page error response |
| **BA** | `nfe.sefaz.ba.gov.br/servicos/nfce/...` | 4KB, redirects to error page server-side |
| **CE** | `nfceh.sefaz.ce.gov.br/pages/ShowNFCe.html` | 1KB, minimal — note "nfceh" subdomain may indicate homologation env |
| **ES** | `app.sefaz.es.gov.br/ConsultaNFCe/qrcode.aspx` | 7KB, server-rendered ASP.NET — has a captcha token in the page but appears to render data inline once chave is valid |
| **PR** | `fazenda.pr.gov.br/nfce/qrcode` | 1.4KB, server-rendered error |
| **RJ** | `consultadfe.fazenda.rj.gov.br/consultaNFCe/QRCode` | 1.6KB, server-rendered |
| **RO** | `nfce.sefin.ro.gov.br/consultanfce/consulta.jsp` | 18KB, server-rendered windows-1252 |
| **RR** | `portalapp.sefaz.rr.gov.br/nfce/servlet/qrcode` | 4KB, server-rendered |
| **SE** | `nfce.sefaz.se.gov.br/portal/consultarNFCe.jsp` | 4KB, server-rendered with "Erro" hint |

Estimated effort once a real chave arrives: **1–2h per UF** to write the
adapter and tests against a captured fixture.

### Tier 2 — server-rendered but stateful (POST + ViewState)

These portals use Java Server Faces or ASP.NET WebForms — the QR Code link
serves a page with a hidden ViewState/CSRF token, and the actual cupom
render comes back only after the page POSTs back to itself. Our current
GET-only flow can reach the page but not the data.

| UF | Portal | Stack |
|---|---|---|
| **MA** | `nfce.sefaz.ma.gov.br/portal/consultarNFCe.jsp` | JSF, jsessionid in URL, 17KB |
| **MG** | `portalsped.fazenda.mg.gov.br/portalnfce/sistema/qrcode.xhtml` | JSF (xhtml + ViewState), 14KB |
| **SP** | `www.nfce.fazenda.sp.gov.br/qrcode` | ASP.NET WebForms, 16KB |

Estimated effort: **3–5h per UF** — need a two-step flow (GET → extract
hidden ViewState → POST back with chave + ViewState). This is well-trodden
ground, just more code than the simple GETs.

### Tier 3 — XML response

| UF | Portal | Notes |
|---|---|---|
| **PE** | `nfce.sefaz.pe.gov.br:444/nfce/consulta` | Returns `text/xml`, 237 bytes for synthetic — tiny |

Estimated effort: **2–3h** — XML is structured, parser is actually easier
than HTML, but it's a different code path so it's its own adapter.

### Tier 4 — captcha (blocking)

Portals that gate the cupom behind a captcha challenge. Solving requires
either a paid captcha-as-a-service (~$0.001/solve via 2Captcha,
Anti-Captcha, etc.) or an interactive flow that asks the user.

| UF | Portal | Captcha type |
|---|---|---|
| **AC** | `dfe.sefaz.ac.gov.br/resolve-captcha?...` | Explicit captcha redirect |
| **MS** | `dfe.ms.gov.br` | Captcha after multi-step redirect (7 hops) |
| **RN** | `nfce.set.rn.gov.br/portalDFE/NFCe/mDadosNFCe.aspx` | Captcha mentioned inline |
| **SC** | `sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx` | Heavy ASP.NET security challenge |
| **TO** | `sefaz.to.gov.br/nfce` | Captcha mentioned multiple times |

Estimated effort: **N/A in current scope** — defer until volume justifies
paying for a captcha service.

### Tier 5 — Single-Page App (data via JS, not HTML)

| UF | Portal | Notes |
|---|---|---|
| **GO** | `nfe.sefaz.go.gov.br/nfeweb/sites/nfce/danfeNFCe` | Returns SPA shell (~6KB). Real data loads via JS after page mount. The AJAX endpoint requires session cookies — direct call to `?chNFe=...` returns "Sessão Expirada". |
| **PI** (likely) | `webas.sefaz.pi.gov.br/...` redirects to `sefaz.pi.gov.br/nfce` | 28KB landing page, likely SPA |

Estimated effort: **6–10h per UF** — needs a headless browser
(Playwright/Selenium) or reverse-engineered AJAX flow with cookie
management. Significant complexity bump.

### Tier 6 — fetch issues, need debug

These returned errors from our probe — could be transient, could be config
issues on our side, could be portals that don't accept a generic HTTP
client.

| UF | Issue | Action |
|---|---|---|
| **AM** | TLS error (cert chain) | Likely SSL config, retry with custom truststore |
| **PA** | Timeout > 15s | Retry with longer timeout, may be slow not down |
| **PB** | Timeout > 15s | Same |
| **DF** | HTTP 403 | Anti-bot block, retry with browser-like headers / cookies |

Estimated effort: **1h per UF** to investigate; might trivially work with
config tweaks.

### Special — historical / dead URLs

| UF | URL | Status |
|---|---|---|
| **RS** (old) | `www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx` | Timed out — looks like a dead legacy URL. Production RS uses `dfe-portal.svrs.rs.gov.br` instead, which is what `SvrsSharedPortalAdapter` already targets. |

## Population coverage

Approximate share of Brazilian population each tier represents:

| Tier | UFs | Approx % of pop | Cumulative |
|---|---|---|---|
| Verified (RS) | 1 | 5.4% | 5.4% |
| Tier 1 simple | 10 | ~22% | 27% |
| Tier 2 stateful | 3 (MA, **MG, SP**) | ~32% | 59% |
| Tier 3 XML | 1 (PE) | ~4.5% | 64% |
| Tier 4 captcha | 5 (AC, MS, RN, **SC**, TO) | ~6.5% | 70% |
| Tier 5 SPA | 1–2 (GO, PI?) | ~5% | 75% |
| Tier 6 debug | 4 (AM, PA, PB, DF) | ~7% | 82% |
| Long tail | unmapped | ~18% | 100% |

The crucial point: **Tier 1 + Tier 2 alone covers ~54% of Brazil's
population**, and includes the 3 biggest states (SP, MG, RJ). That's the
priority order for adapter implementation.

## Path forward

Adapters CANNOT be written reliably without a real cupom HTML per UF. Any
parser written against the synthetic-error responses will break on first
contact with real data because Jsoup selectors need real markup to target.

### To unblock: collect real chaves

Per `docs/MULTI_STATE_RECON.md` (this file) — easiest sources, in order:

1. **e-CAC (federal)**: `cav.receita.fazenda.gov.br` → "Consulta DF-e"
   shows ALL NFC-e issued with one's CPF. Export in batch.
2. **State cashback portals** (NFP-SP, NotaFiscalGaucha-RS, NotaFiscalMineira,
   NotaLegalDF, etc.) — usually have download buttons.
3. **Family/contacts in each state** — one fresh chave per UF unblocks one
   adapter.

Aim: **1 real chave per UF, ≤ 3 months old** (older may be outside SEFAZ
retention).

### To capture HTML when a real chave is in hand

```bash
curl -sL --max-time 30 -A "economizai/0.1 (xandivieira@gmail.com)" \
  "<portal-url>?p=<chave>|2|1|<env>|<hash>" \
  > src/test/resources/fixtures/sefaz/<uf-lowercase>/nfce-sample.html
```

Then write the adapter against the saved fixture (per CLAUDE.md
convention), iterate selectors until the test passes, register the UF in
the relevant adapter's `supportedStates()`.

### Implementation order recommendation

When real chaves arrive, suggested order:

1. **SP, MG, RJ, MA** (Tier 1 + Tier 2) — biggest population, biggest
   payoff. SP and MG carry the JSF/ViewState complexity cost.
2. **PE** (Tier 3 XML) — different code path but easy parser.
3. **AL, BA, CE, ES, PR, RO, RR, SE** (Tier 1) — quick wins, low
   complexity each.
4. **AP** (Tier 1) — ridiculous response size (136 bytes) probably means
   either a redirect chain we missed or a near-empty error. Investigate
   with real chave first.
5. **AM, PA, PB, DF** (Tier 6) — config debugging.
6. **GO + PI** (Tier 5 SPA) — only when revenue/usage justifies headless
   browser infra.
7. **AC, MS, RN, SC, TO** (Tier 4 captcha) — only when 2Captcha-style
   service is wired up.

## Final verdict (the question we set out to answer)

**Of the 27 Brazilian UFs, NFC-e ingestion is verified end-to-end for 1
state today (RS).** Any other UF will fail with `UnsupportedStateException`
unless added to `SEFAZ_SVRS_STATES` env var — and even then, it will
likely fail at the parser step because the SVRS portal does not host other
states' NFC-e (despite our earlier hypothesis — empirical probe disproved
it; each state's QR Code points to its own portal subdomain).

**The path forward is data-collection-bound, not code-bound.**

---

Last verified: 2026-05-06.
