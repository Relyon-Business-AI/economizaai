package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.ExperimentalPortalEvidence;
import com.relyon.economizaai.exception.ExperimentalCaptchaWallException;
import com.relyon.economizaai.exception.ExperimentalStateFailedException;
import com.relyon.economizaai.exception.InfosimplesNoDataException;
import com.relyon.economizaai.exception.InfosimplesProviderException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.PaidApiUnavailableException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.exception.SefazPortalRejectionException;
import com.relyon.economizaai.exception.UnsupportedStateException;
import com.relyon.economizaai.model.enums.InfosimplesOutcome;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.llm.HtmlReceiptRescueService;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolveTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SefazIngestionService {

    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    private final Map<UnidadeFederativa, SefazAdapter> adapters;
    private final Set<UnidadeFederativa> verifiedStates;
    private final Optional<InfosimplesService> infosimples;
    private final PaidApiGuardService paidApiGuard;
    private final StateCoverageService stateCoverage;
    private final RsChaveReconsultService rsChaveReconsult;
    private final ChaveReconsultService chaveReconsult;
    private final OperationalAlertService operationalAlert;
    private final PrefetchPolicy prefetchPolicy;
    private final HtmlReceiptRescueService htmlReceiptRescue;
    // Alerta de "chave manual sem caminho" no máximo 1×/UF/dia (padrão do codebase).
    private final Map<UnidadeFederativa, LocalDate> lastNoPathAlert = new ConcurrentHashMap<>();

    public SefazIngestionService(List<SefazAdapter> adapters,
                                 Optional<InfosimplesService> infosimples,
                                 PaidApiGuardService paidApiGuard,
                                 StateCoverageService stateCoverage,
                                 RsChaveReconsultService rsChaveReconsult,
                                 ChaveReconsultService chaveReconsult,
                                 OperationalAlertService operationalAlert,
                                 PrefetchPolicy prefetchPolicy,
                                 HtmlReceiptRescueService htmlReceiptRescue) {
        var byState = new EnumMap<UnidadeFederativa, SefazAdapter>(UnidadeFederativa.class);
        for (var adapter : adapters) {
            for (var uf : adapter.supportedStates()) {
                var prior = byState.put(uf, adapter);
                if (prior != null && prior != adapter) {
                    throw new IllegalStateException("Two adapters claim " + uf + ": "
                            + prior.getClass().getSimpleName() + " and " + adapter.getClass().getSimpleName());
                }
            }
        }
        this.verifiedStates = Set.copyOf(byState.keySet());
        // Every UF no dedicated adapter claims falls to the experimental
        // catch-all: fetch the QR's own portal URL, shared parser, Infosimples
        // rescue. Gap-fill only — it never competes with a verified adapter.
        adapters.stream()
                .filter(GenericQrPortalAdapter.class::isInstance)
                .map(GenericQrPortalAdapter.class::cast)
                .filter(GenericQrPortalAdapter::isEnabled)
                .findFirst()
                .ifPresent(generic -> {
                    for (var uf : UnidadeFederativa.values()) {
                        byState.putIfAbsent(uf, generic);
                    }
                });
        this.adapters = Map.copyOf(byState);
        this.infosimples = infosimples;
        this.paidApiGuard = paidApiGuard;
        this.stateCoverage = stateCoverage;
        this.rsChaveReconsult = rsChaveReconsult;
        this.chaveReconsult = chaveReconsult;
        this.operationalAlert = operationalAlert;
        this.prefetchPolicy = prefetchPolicy;
        this.htmlReceiptRescue = htmlReceiptRescue;
        log.info("Registered SEFAZ adapters: verified={} experimental={} infosimples-fallback={}",
                verifiedStates, experimentalStates(), infosimples.isPresent());
    }

    /** UFs served by a dedicated, fixture-verified adapter. */
    public Set<UnidadeFederativa> getVerifiedStates() {
        return verifiedStates;
    }

    /** UFs served only by the experimental fallback chain. */
    public Set<UnidadeFederativa> experimentalStates() {
        var experimental = EnumSet.noneOf(UnidadeFederativa.class);
        adapters.forEach((uf, adapter) -> {
            if (isExperimental(adapter)) experimental.add(uf);
        });
        return experimental;
    }

    private static boolean isExperimental(SefazAdapter adapter) {
        return adapter instanceof GenericQrPortalAdapter;
    }

    /** True when the UF is served ONLY by the experimental fallback (unproven — no dedicated adapter). */
    public boolean isExperimental(UnidadeFederativa uf) {
        var adapter = adapters.get(uf);
        return adapter != null && isExperimental(adapter);
    }

    /**
     * Throws {@link UnsupportedStateException} when no adapter covers the UF.
     * Called synchronously at submit time so users in unsupported states get an
     * immediate localized 400 instead of an async FAILED_PARSE with a raw key.
     */
    public void requireSupported(UnidadeFederativa uf) {
        if (!adapters.containsKey(uf)) {
            throw new UnsupportedStateException(uf.name());
        }
    }

    /**
     * Whether a bare 44-digit chave (manual entry / OCR — no QR signature) can be
     * served for this chave. The portal can't be scraped without the QR signature
     * (SVRS family), so it works when ANY of these covers it:
     * <ul>
     *   <li>the portal doesn't need the signature at all (MS/SC consult by chave);</li>
     *   <li>a FREE legacy reconsult exists (RS SAT-WEB, CE qrcodev3, BA captcha) —
     *       {@link ChaveReconsultService#isReconsultable};</li>
     *   <li>the paid Infosimples by-chave fallback is enabled (covers all 27 UFs).</li>
     * </ul>
     * RS is NO LONGER a hard no: it has a free legacy portal (SAT-WEB, logged-out)
     * AND Infosimples (nfce-resumida). The actual fetch chain (see {@link #fetch})
     * tries free reconsult first, then Infosimples.
     */
    public boolean supportsBareChave(String chave) {
        var adapter = adapters.get(ChaveAcessoParser.extractUf(chave));
        if (adapter == null) return false;
        return !adapter.requiresQrSignature()
                || ChaveReconsultService.isReconsultable(chave)
                || infosimples.isPresent();
    }

    /**
     * NF-e (modelo 55) digitada: RS resolve de graça (SVRS); o resto depende da
     * consulta nacional paga. Sem provedor, rejeita no submit com mensagem honesta.
     */
    public boolean supportsTypedNfe55(UnidadeFederativa uf) {
        return uf == UnidadeFederativa.RS || infosimples.isPresent();
    }

    /**
     * Resolve a QR payload to the 44-digit chave. Most payloads contain it
     * directly; captcha/security wrappers such as SC's SecurityVerify URL need
     * a portal-specific preflight fetch before submit can route by UF.
     */
    public String resolveChave(String qrPayload) {
        try {
            return ChaveAcessoParser.extractChave(qrPayload);
        } catch (InvalidQrPayloadException | CaptchaUnavailableException ex) {
            for (var adapter : adapters.values().stream().distinct().toList()) {
                var resolved = adapter.preflightChave(qrPayload);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
            throw ex;
        }
    }

    /**
     * Step 1: pick the right state adapter, fetch + sanitize the HTML.
     * Split from {@link #parse} so callers (ReceiptService) can persist
     * the raw HTML even when parsing fails downstream — needed by PRO-43.
     *
     * <p>If the primary adapter throws and the Infosimples fallback is enabled,
     * the receipt is fetched via Infosimples instead (paid, ~R$0.24/query).
     * The resulting {@link FetchedDocument} carries a pre-parsed
     * {@link ParsedReceipt} so {@link #parse} returns it directly.
     *
     * <p>Paid calls (captcha solves, Infosimples queries) are attributed to
     * {@code userId} for the per-user daily cap and the reconciliation ledger.
     * A null {@code userId} (admin reparse, tests) skips the cap but is still logged.
     */
    public FetchedDocument fetch(String qrPayload, UUID userId) {
        return fetch(qrPayload, userId, false);
    }

    /**
     * As {@link #fetch(String, UUID)} but {@code deviceFetchAvailable} says the
     * submitting client can resolve a blocked-state failure on its own residential
     * IP. With the generic device-fetch fallback ON, a rescuable fetch failure
     * (IP block / portal unreachable) then defers to {@code NEEDS_DEVICE_FETCH}
     * BEFORE spending the paid Infosimples fallback — the device fetch succeeds
     * exactly where our datacenter IP is blocked, and Infosimples stays the last
     * resort if it also fails (see {@code ReceiptIngestionService#ingestPrefetched}).
     * A captcha wall the phone can't pass either (CaptchaUnavailableException) is
     * NOT deferred. Flag OFF → this parameter is inert and behavior is unchanged.
     */
    public FetchedDocument fetch(String qrPayload, UUID userId, boolean deviceFetchAvailable) {
        return fetch(qrPayload, userId, deviceFetchAvailable, IngestSpendPolicy.FULL);
    }

    /**
     * As {@link #fetch(String, UUID, boolean)} but {@code spendPolicy} states which paid layers
     * this attempt may use. A layer the policy forbids is simply skipped: the free paths still
     * run, and a failure propagates as the primary failure (no "state unsupported" evidence is
     * recorded for a layer that was never allowed to try).
     */
    public FetchedDocument fetch(String qrPayload, UUID userId, boolean deviceFetchAvailable,
                                 IngestSpendPolicy spendPolicy) {
        var infosimplesAllowed = infosimples.isPresent() && spendPolicy.infosimples();
        var chave = resolveChave(qrPayload);
        var uf = ChaveAcessoParser.extractUf(chave);
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        // Chave pura (QR danificado / digitada / OCR) não tem assinatura → o portal
        // SVRS não pode ser raspado. Cadeia única, igual pra todo estado:
        //   1) reconsulta legada GRÁTIS (RS SAT-WEB / CE qrcodev3 / BA captcha), se houver;
        //   2) Infosimples (pago, ~R$0,24) cobrindo as 27 UFs;
        //   3) nada funcionou → alerta WhatsApp admin (1×/UF/dia) e falha honesta.
        if (adapter.requiresQrSignature() && ChaveAcessoParser.isBareChave(qrPayload)) {
            if (ChaveReconsultService.isReconsultable(chave)) {
                try {
                    var reconsulted = reconsultByChave(chave, uf, userId, spendPolicy);
                    log.info("sefaz.fetch.bare_chave.reconsult_ok uf={} chave={}", uf, abbrev(chave));
                    return reconsulted;
                } catch (RuntimeException legacyEx) {
                    log.info("sefaz.fetch.bare_chave.reconsult_failed uf={} chave={} reason={} — tentando Infosimples",
                            uf, abbrev(chave), describe(legacyEx));
                }
            }
            if (infosimplesAllowed) {
                try {
                    var rescued = fetchViaInfosimples(chave, uf, userId);
                    if (isExperimental(adapter)) {
                        stateCoverage.recordSuccess(uf, StateIngestionStrategy.INFOSIMPLES, null);
                    }
                    return rescued;
                } catch (PaidApiQuotaExceededException | PaidApiBudgetExceededException | PaidApiUnavailableException guardEx) {
                    throw guardEx; // cap/budget/breaker — não é "sem suporte", propaga o motivo real
                } catch (InfosimplesNoDataException noDataEx) {
                    // Chave digitada errada (passa no DV) ou nota cancelada: falha determinística
                    // do INPUT, não do caminho — alertar aqui queimaria o dedup 1×/UF/dia e
                    // mascararia uma queda real do portal no resto do dia.
                    log.info("sefaz.fetch.bare_chave.infosimples_no_data uf={} chave={}", uf, abbrev(chave));
                    throw noDataEx;
                } catch (RuntimeException infosimplesEx) {
                    if (isDeterministicInfosimplesAnswer(infosimplesEx)) {
                        // A nota without products is a verdict about the nota too — no path alert.
                        log.info("sefaz.fetch.bare_chave.infosimples_no_items uf={} chave={}", uf, abbrev(chave));
                        throw infosimplesEx;
                    }
                    log.warn("sefaz.fetch.bare_chave.infosimples_failed uf={} chave={} reason={}",
                            uf, abbrev(chave), describe(infosimplesEx));
                    alertManualChaveNoPath(uf, chave, describe(infosimplesEx));
                    throw infosimplesEx;
                }
            }
            if (!spendPolicy.infosimples()) {
                // The paid layer was withheld on purpose (free-only attempt) — not a missing path.
                log.info("sefaz.fetch.bare_chave.paid_skipped uf={} chave={}", uf, abbrev(chave));
                throw new SefazFetchException(uf.name());
            }
            log.warn("sefaz.fetch.bare_chave.no_path uf={} chave={} — sem legado e Infosimples desabilitado", uf, abbrev(chave));
            alertManualChaveNoPath(uf, chave, "sem reconsulta legada e Infosimples desabilitado");
            throw new SefazFetchException(uf.name());
        }
        // A scrape MAY solve captchas (paid, per token) — the solver tracks the
        // actual solves and they're ledgered after the fetch. The captcha daily budget
        // must NOT stop a captcha-free scrape (RS/PR/SP/GO/CE cost nothing): an
        // exhausted budget only forbids the solver from buying a token.
        var budgetExhausted = spendPolicy.captchaSolves()
                && paidApiGuard.isDailyBudgetExhausted(PaidApiService.CAPTCHA_SOLVE);
        var captchaSpendAllowed = spendPolicy.captchaSolves() && !budgetExhausted;
        if (captchaSpendAllowed) {
            paidApiGuard.assertWithinDailyCap(userId, PaidApiService.CAPTCHA_SOLVE);
        }
        CaptchaSolveTracker.drain(); // discard any stale count left on this pooled thread
        CaptchaSolveTracker.blockSpend(!captchaSpendAllowed);
        try {
            var html = adapter.fetchHtml(qrPayload);
            recordCaptchaSolves(userId, uf, true);
            var sanitized = CpfMasker.strip(html);
            var sourceUrl = qrPayload.trim().toLowerCase().startsWith("http") ? qrPayload.trim() : null;
            return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl, null);
        } catch (SefazFetchException | CaptchaUnavailableException | CaptchaSolveFailedException primaryEx) {
            // Ledger exactly what was SPENT: one failure entry per token actually
            // bought during this fetch. Captcha-free scrapes and immediate throws
            // (e.g. the Infosimples-only adapter) record nothing — no breaker noise.
            recordCaptchaSolves(userId, uf, false);
            if (budgetExhausted && primaryEx instanceof CaptchaSolveFailedException) {
                log.warn("sefaz.fetch.captcha_blocked_by_budget uf={} chave={}", uf, abbrev(chave));
                throw new PaidApiBudgetExceededException();
            }
            var experimental = isExperimental(adapter);
            var qrHost = StateCoverageService.hostOf(qrPayload);
            var portalEvidence = primaryEx instanceof ExperimentalPortalEvidence evidenceCarrier
                    ? evidenceCarrier.portalEvidence() : null;
            if (experimental) {
                stateCoverage.recordFailure(uf, StateIngestionStrategy.QR_PORTAL,
                        StateIngestionOutcome.FETCH_FAILED, qrHost, joinDetail(describe(primaryEx), portalEvidence));
            }
            // Only failures Infosimples can plausibly rescue: portal down after
            // retries, no solver configured, or the solver itself failed. Every
            // query costs ~R$0.24, so deterministic failures (bad chave, invalid
            // QR, missing sitekey/viewstate) propagate without spending.
            if (primaryEx instanceof SefazDeterministicFetchException) {
                log.info("sefaz.fetch.deterministic_4xx uf={} chave={} — skipping paid fallback", uf, abbrev(chave));
                throw primaryEx;
            }
            // Generic reactive device-fetch (flag-gated): an IP-block / portal-unreachable
            // failure may succeed from the user's residential IP, so defer to on-device fetch
            // BEFORE spending the paid Infosimples fallback. Only a plain SefazFetchException
            // qualifies — a captcha wall the phone can't pass either (CaptchaUnavailableException,
            // not a SefazFetchException) falls through to Infosimples as before. Flag OFF makes
            // isGenericEnabled() false, so this branch never fires and behavior is unchanged.
            if (deviceFetchAvailable && prefetchPolicy.isGenericEnabled()
                    && primaryEx instanceof SefazFetchException) {
                log.info("sefaz.fetch.defer_device uf={} chave={} — server blocked, deferring to on-device fetch before paid fallback",
                        uf, abbrev(chave));
                throw experimentalExhausted(uf, userId, chave, sourceUrlOf(qrPayload),
                        "QR_PORTAL: " + describe(primaryEx) + "; deferred to on-device fetch (pre-Infosimples)",
                        portalEvidence, false);
            }
            if (!spendPolicy.infosimples()) {
                log.info("sefaz.fetch.paid_fallback_skipped uf={} chave={}", uf, abbrev(chave));
                throw primaryEx;
            }
            if (infosimples.isEmpty()) {
                if (experimental) {
                    throw experimentalExhausted(uf, userId, chave, sourceUrlOf(qrPayload),
                            "QR_PORTAL: " + describe(primaryEx) + "; INFOSIMPLES: desabilitado", portalEvidence,
                            primaryEx instanceof ExperimentalCaptchaWallException);
                }
                throw primaryEx;
            }
            log.warn("sefaz.fetch.primary.failed uf={} chave={} reason={} — trying infosimples fallback",
                    uf, abbrev(chave), primaryEx.getMessage());
            try {
                var rescued = fetchViaInfosimples(chave, uf, userId);
                if (experimental) {
                    stateCoverage.recordSuccess(uf, StateIngestionStrategy.INFOSIMPLES, qrHost);
                }
                return rescued;
            } catch (PaidApiQuotaExceededException | PaidApiBudgetExceededException | PaidApiUnavailableException guardEx) {
                // A cap/budget/breaker block is not evidence the state is broken —
                // surface the real (localized) reason instead of "unsupported state".
                throw guardEx;
            } catch (RuntimeException fallbackEx) {
                if (experimental) {
                    stateCoverage.recordFailure(uf, StateIngestionStrategy.INFOSIMPLES,
                            StateIngestionOutcome.FETCH_FAILED, qrHost, describe(fallbackEx));
                    throw experimentalExhausted(uf, userId, chave, sourceUrlOf(qrPayload),
                            "QR_PORTAL: " + describe(primaryEx) + "; INFOSIMPLES: " + describe(fallbackEx),
                            portalEvidence, primaryEx instanceof ExperimentalCaptchaWallException,
                            isPortalUnavailable(fallbackEx), infosimplesOutcomeOf(fallbackEx));
                }
                throw fallbackEx;
            }
        } catch (RuntimeException deterministicEx) {
            // Deterministic failures (invalid QR, missing viewstate/sitekey) skip the
            // fallback — but any token bought before them was still paid for.
            recordCaptchaSolves(userId, uf, false);
            throw deterministicEx;
        }
    }

    /**
     * The legacy bare-chave reconsult. Free for RS/CE/AM; BA's consult solves an image captcha
     * per attempt, so it is metered exactly like the QR path: the per-user daily cap, the global
     * budget and the spend policy decide whether a solve may be bought, and every solve actually
     * performed is ledgered afterwards. With the spend refused the solver declines, the reconsult
     * fails and the chain moves on to the next layer.
     */
    private FetchedDocument reconsultByChave(String chave, UnidadeFederativa uf, UUID userId,
                                             IngestSpendPolicy spendPolicy) {
        var captchaGated = ChaveReconsultService.solvesCaptcha(chave);
        CaptchaSolveTracker.drain(); // discard any stale count left on this pooled thread
        CaptchaSolveTracker.blockSpend(captchaGated && !isCaptchaSpendAllowed(userId, uf, spendPolicy));
        try {
            var reconsulted = chaveReconsult.reconsult(chave);
            recordCaptchaSolves(userId, uf, true);
            return reconsulted;
        } catch (RuntimeException reconsultEx) {
            recordCaptchaSolves(userId, uf, false);
            throw reconsultEx;
        } finally {
            CaptchaSolveTracker.blockSpend(false);
        }
    }

    private boolean isCaptchaSpendAllowed(UUID userId, UnidadeFederativa uf, IngestSpendPolicy spendPolicy) {
        if (!spendPolicy.captchaSolves() || paidApiGuard.isDailyBudgetExhausted(PaidApiService.CAPTCHA_SOLVE)) return false;
        try {
            paidApiGuard.assertWithinDailyCap(userId, PaidApiService.CAPTCHA_SOLVE);
            return true;
        } catch (PaidApiQuotaExceededException capReached) {
            log.info("sefaz.fetch.bare_chave.captcha_cap_reached uf={}", uf);
            return false;
        }
    }

    /** One ledger entry per PAID captcha solve performed during the fetch (see {@link CaptchaSolveTracker}). */
    private void recordCaptchaSolves(UUID userId, UnidadeFederativa uf, boolean fetchSucceeded) {
        var solves = CaptchaSolveTracker.drain();
        for (var solve = 0; solve < solves; solve++) {
            if (fetchSucceeded) {
                paidApiGuard.recordSuccess(userId, PaidApiService.CAPTCHA_SOLVE, uf.name(), null);
            } else {
                paidApiGuard.recordFailure(userId, PaidApiService.CAPTCHA_SOLVE, uf.name(), null);
            }
        }
    }

    private static String joinDetail(String description, String portalEvidence) {
        return portalEvidence == null ? description : description + "\n" + portalEvidence;
    }

    /**
     * Alerta o admin (WhatsApp + e-mail) quando uma chave manual não teve NENHUM
     * caminho (reconsulta legada e Infosimples falharam/indisponíveis). No máximo
     * 1×/UF/dia pra não floodar. Nunca lança — alertAdmin trata os próprios erros.
     */
    private void alertManualChaveNoPath(UnidadeFederativa uf, String chave, String reason) {
        var today = LocalDate.now(BRAZIL);
        if (today.equals(lastNoPathAlert.put(uf, today))) {
            return; // já alertou hoje por essa UF
        }
        operationalAlert.alertAdmin(
                "Chave manual sem caminho: " + uf,
                ("Uma chave digitada/fotografada de %s não pôde ser processada — nem a reconsulta legada "
                        + "nem o Infosimples resolveram.%nchave=%s%nmotivo=%s%n%nVale ver se esse estado "
                        + "precisa de um fallback/legado novo.").formatted(uf, abbrev(chave), reason));
    }

    /**
     * Public entry to the paid by-chave fallback, for the device-fetch chain: when
     * on-device content fails to parse, the ingestion continues here (Infosimples is
     * server-side by-chave, so it doesn't need the client). Throws
     * {@link SefazFetchException} when no provider is configured, so the caller treats
     * "no continuation available" the same as a failed one. The returned document
     * carries a pre-parsed receipt.
     */
    public FetchedDocument fetchViaInfosimplesFallback(String chave, UnidadeFederativa uf, UUID userId) {
        if (infosimples.isEmpty()) {
            throw new SefazFetchException(uf.name());
        }
        var experimental = isExperimental(uf);
        try {
            var rescued = fetchViaInfosimples(chave, uf, userId);
            // Esse caminho (sweeper do device-fetch / device-content ilegível) também é a cadeia
            // da UF experimental: sem registrar, PA/RN apareciam 0% e o spend-cap nunca via o sucesso.
            if (experimental) stateCoverage.recordSuccess(uf, StateIngestionStrategy.INFOSIMPLES, null);
            return rescued;
        } catch (PaidApiQuotaExceededException | PaidApiBudgetExceededException | PaidApiUnavailableException guardEx) {
            throw guardEx;
        } catch (RuntimeException fallbackEx) {
            if (experimental) {
                stateCoverage.recordFailure(uf, StateIngestionStrategy.INFOSIMPLES,
                        StateIngestionOutcome.FETCH_FAILED, null, describe(fallbackEx));
            }
            throw fallbackEx;
        }
    }

    /**
     * The paid by-chave fallback. Guarded by the Infosimples circuit breaker (skip
     * when the provider is failing) and the per-user daily cap, and every attempt —
     * success or failure — is written to the ledger. A failure trips the breaker.
     */
    private FetchedDocument fetchViaInfosimples(String chave, UnidadeFederativa uf, UUID userId) {
        // A chave whose mod-11 check digit is wrong was corrupted on read (QR decode / typo):
        // Infosimples ALWAYS rejects it as 607 "parâmetro inválido". Don't spend a paid call
        // on a dead chave — fail fast with an honest "re-scan" reason (no ledger row: no call).
        if (!ChaveAcessoParser.hasValidCheckDigit(chave)) {
            log.warn("infosimples.skip.invalid_chave uf={} chave={} — DV inválido (leitura corrompida), sem chamada paga",
                    uf, abbrev(chave));
            throw new ReceiptParseException("chave-corrupted");
        }
        paidApiGuard.assertUnderDailyBudget(PaidApiService.INFOSIMPLES);
        paidApiGuard.assertCircuitClosed(PaidApiService.INFOSIMPLES, uf.name());
        paidApiGuard.assertWithinDailyCap(userId, PaidApiService.INFOSIMPLES);
        try {
            var preParsed = infosimples.get().fetchParsed(chave, uf);
            paidApiGuard.recordSuccess(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples", 200);
            log.info("sefaz.fetch.infosimples.ok uf={} chave={} items={}",
                    uf, abbrev(chave), preParsed.items().size());
            return new FetchedDocument(null, null, chave, uf, preParsed.sourceUrl(), preParsed);
        } catch (InfosimplesNoDataException ex) {
            // "Nota não existe/chave inválida" é resposta válida do provedor — entra no ledger
            // mas não derruba o breaker (senão um lote de notas mortas starva as vivas).
            paidApiGuard.recordDeterministicFailure(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples", ex.getCode());
            throw ex;
        } catch (InfosimplesProviderException ex) {
            // Provider/portal momentarily unstable (609…) — counts toward the breaker.
            paidApiGuard.recordFailure(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples", ex.getCode());
            throw ex;
        } catch (RuntimeException ex) {
            if (isDeterministicInfosimplesAnswer(ex)) {
                // The provider answered fine, the nota just carries no products — a per-nota
                // verdict like "no data": ledgered (the call was billed), not a breaker failure.
                paidApiGuard.recordDeterministicFailure(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples", 200);
                throw ex;
            }
            // No provider code (I/O error, timeout) — record null.
            paidApiGuard.recordFailure(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples", null);
            throw ex;
        }
    }

    /**
     * Step 2: parse the fetched HTML, attributing any paid rescue to {@code userId}.
     * Returns the pre-parsed receipt immediately when the fetch was served by the
     * Infosimples fallback. An unparseable layout (portal responded but the parser
     * couldn't read it) is rescued by the LLM from the same HTML (~R$0.05); for
     * experimental states a failed LLM rescue still falls to Infosimples (~R$0.24)
     * before giving up — the chain is ordered by cost.
     */
    public ParsedReceipt parse(FetchedDocument fetched, UUID userId) {
        return parse(fetched, userId, IngestSpendPolicy.FULL);
    }

    /**
     * As {@link #parse(FetchedDocument, UUID)} but {@code spendPolicy} states which paid rescues
     * (LLM, Infosimples) this attempt may use. With a rescue withheld, the original parse
     * failure propagates untouched.
     */
    public ParsedReceipt parse(FetchedDocument fetched, UUID userId, IngestSpendPolicy spendPolicy) {
        if (fetched.preParsed() != null) return fetched.preParsed();
        var experimental = isExperimental(fetched.adapter());
        var qrHost = StateCoverageService.hostOf(fetched.sourceUrl());
        var fetchLayer = fetched.fromDevice() ? StateIngestionStrategy.DEVICE_FETCH : StateIngestionStrategy.QR_PORTAL;
        try {
            var parsed = fetched.adapter().parseHtml(fetched.html(), fetched.chave(), fetched.sourceUrl());
            if (experimental) {
                stateCoverage.recordSuccess(fetched.uf(), fetchLayer, qrHost);
            }
            return parsed;
        } catch (ReceiptParseException parseEx) {
            if (parseEx instanceof SefazPortalRejectionException) {
                var rescued = rescueRejectionViaChaveReconsult(fetched);
                if (rescued != null) return rescued;
            }
            // A contingency note (tpEmis != 1) with no items isn't a parser regression — SEFAZ has
            // no items for a note the store emitted offline. Don't pollute the verified-parser
            // regression signal (nor spend a fallback on it); fail it honestly downstream.
            if (parseEx.isNoItemsFound() && ChaveAcessoParser.isContingencyEmission(fetched.chave())) {
                throw parseEx;
            }
            if (!experimental) {
                // A verified state's parser failed on real HTML — the regression
                // signature (portal changed its DANFE format). Record it either way
                // (a burst alerts the admin with the HTML to fix the adapter), then
                // let the LLM rescue the user's receipt from the same HTML.
                stateCoverage.recordVerifiedParseFailure(fetched.uf(), fetched.chave(), fetched.sourceUrl(),
                        parseEx.getMessageKey() + ":" + String.join(",", parseEx.getArguments()), fetched.html());
                var llmRescued = rescueParseViaLlm(fetched, parseEx, userId, spendPolicy);
                if (llmRescued != null) return llmRescued;
                throw parseEx;
            }
            stateCoverage.recordFailure(fetched.uf(), fetchLayer,
                    StateIngestionOutcome.PARSE_FAILED, qrHost, describe(parseEx));
            var llmRescued = rescueParseViaLlm(fetched, parseEx, userId, spendPolicy);
            if (llmRescued != null) {
                stateCoverage.recordSuccess(fetched.uf(), StateIngestionStrategy.LLM_RESCUE, qrHost);
                return llmRescued;
            }
            if (!spendPolicy.infosimples()) {
                log.info("sefaz.parse.paid_fallback_skipped uf={} chave={}", fetched.uf(), abbrev(fetched.chave()));
                throw parseEx;
            }
            var infosimplesNote = "desabilitado";
            InfosimplesOutcome infosimplesOutcome = null;
            if (infosimples.isPresent()) {
                log.warn("sefaz.parse.experimental.failed uf={} chave={} reason={} — trying infosimples fallback",
                        fetched.uf(), abbrev(fetched.chave()), parseEx.getMessageKey());
                try {
                    var rescued = fetchViaInfosimples(fetched.chave(), fetched.uf(), userId);
                    stateCoverage.recordSuccess(fetched.uf(), StateIngestionStrategy.INFOSIMPLES, qrHost);
                    return rescued.preParsed();
                } catch (PaidApiQuotaExceededException | PaidApiBudgetExceededException | PaidApiUnavailableException guardEx) {
                    throw guardEx;
                } catch (RuntimeException fallbackEx) {
                    stateCoverage.recordFailure(fetched.uf(), StateIngestionStrategy.INFOSIMPLES,
                            StateIngestionOutcome.FETCH_FAILED, qrHost, describe(fallbackEx));
                    infosimplesNote = describe(fallbackEx);
                    infosimplesOutcome = infosimplesOutcomeOf(fallbackEx);
                }
            }
            throw experimentalExhausted(fetched.uf(), userId, fetched.chave(), fetched.sourceUrl(),
                    "QR_PORTAL (parse): " + describe(parseEx) + "; INFOSIMPLES: " + infosimplesNote,
                    fetched.html(), false, false, infosimplesOutcome);
        }
    }

    /**
     * The LLM layer of the parse-fallback chain (between the free parser and the
     * paid Infosimples re-fetch). Skipped for portal REJECTION pages — those carry
     * no DANFE for the model to read, so spending on them is guaranteed waste; the
     * reconsult arbiter and (for experimental states) Infosimples still apply.
     * Returns null when the rescue didn't produce a validated receipt.
     */
    private ParsedReceipt rescueParseViaLlm(FetchedDocument fetched, ReceiptParseException parseEx, UUID userId,
                                            IngestSpendPolicy spendPolicy) {
        if (!spendPolicy.llmRescue()) return null;
        if (parseEx instanceof SefazPortalRejectionException) return null;
        var rescued = htmlReceiptRescue.rescue(fetched.html(), fetched.chave(), fetched.uf(), fetched.sourceUrl(), userId);
        if (rescued != null) {
            log.info("sefaz.parse.llm_rescued uf={} chave={} items={}",
                    fetched.uf(), abbrev(fetched.chave()), rescued.items().size());
        }
        return rescued;
    }

    /**
     * A portal rejection page sometimes lies: the QR printed by the merchant
     * carries a broken DigestValue (cStat 227), so the QR consult is rejected
     * forever while the nota itself is authorized at SEFAZ. For RS the bare-chave
     * legacy portal is a free arbiter — if it renders the DANFE, the rejection
     * didn't reflect reality and the nota is rescued; any reconsult failure means
     * the rejection was real (e.g. contingency truly not transmitted yet), so the
     * caller propagates the ORIGINAL rejection untouched. No paid API involved.
     */
    private ParsedReceipt rescueRejectionViaChaveReconsult(FetchedDocument fetched) {
        if (!RsChaveReconsultService.isReconsultable(fetched.chave())) {
            return null;
        }
        try {
            var reconsulted = rsChaveReconsult.reconsult(fetched.chave());
            var parsed = reconsulted.adapter()
                    .parseHtml(reconsulted.html(), reconsulted.chave(), reconsulted.sourceUrl());
            log.info("sefaz.parse.rejection_rescued_by_chave uf={} chave={} items={}",
                    fetched.uf(), abbrev(fetched.chave()), parsed.items().size());
            return parsed;
        } catch (RuntimeException reconsultEx) {
            log.info("sefaz.parse.chave_reconsult_failed chave={} reason={} — keeping original rejection",
                    abbrev(fetched.chave()), describe(reconsultEx));
            return null;
        }
    }

    /**
     * Terminal failure of the experimental chain: record + alert the admin with
     * the evidence, and surface the user-facing "not supported yet" key.
     *
     * <p>Device-fetch UFs ({@link PrefetchPolicy} — portals that block our
     * datacenter IP by design, e.g. PE) record NO evidence: their server-side
     * failure is expected and ends in the NEEDS_DEVICE_FETCH handoff, so counting
     * it would let a few abandoned scans lock the state at submit forever.
     */
    private ExperimentalStateFailedException experimentalExhausted(UnidadeFederativa uf, UUID userId, String chave,
                                                                   String sourceUrl, String failureSummary,
                                                                   String htmlSnippet, boolean captchaBlocked) {
        return experimentalExhausted(uf, userId, chave, sourceUrl, failureSummary, htmlSnippet, captchaBlocked,
                false, null);
    }

    /** {@code infosimplesOutcome}: how the paid layer answered inside this chain; null when it was not tried. */
    private ExperimentalStateFailedException experimentalExhausted(UnidadeFederativa uf, UUID userId, String chave,
                                                                   String sourceUrl, String failureSummary,
                                                                   String htmlSnippet, boolean captchaBlocked,
                                                                   boolean portalUnavailable,
                                                                   InfosimplesOutcome infosimplesOutcome) {
        if (prefetchPolicy.isDeviceFetchUf(uf)) {
            log.info("state_coverage.exhausted_skipped uf={} reason=device_fetch_uf", uf);
        } else {
            stateCoverage.reportExhausted(uf, userId, chave, sourceUrl, failureSummary, htmlSnippet);
        }
        return new ExperimentalStateFailedException(uf.name(), captchaBlocked, portalUnavailable, infosimplesOutcome);
    }

    private static InfosimplesOutcome infosimplesOutcomeOf(RuntimeException fallbackEx) {
        return isDeterministicInfosimplesAnswer(fallbackEx) ? InfosimplesOutcome.NO_DATA : InfosimplesOutcome.FAILED;
    }

    /**
     * The Infosimples fallback failed: the portal/origin is momentarily UNAVAILABLE (transient —
     * provider instability, code 615) UNLESS it answered a deterministic per-nota verdict
     * ({@link InfosimplesNoDataException}: nota inexistente/cancelada). Only the transient case is
     * worth parking as PORTAL_PENDING and retrying — a not-found never changes.
     */
    private static boolean isPortalUnavailable(RuntimeException fallbackEx) {
        return !isDeterministicInfosimplesAnswer(fallbackEx);
    }

    /** The provider answered and the answer is final for this nota: no data, or a nota without products. */
    private static boolean isDeterministicInfosimplesAnswer(RuntimeException fallbackEx) {
        return fallbackEx instanceof InfosimplesNoDataException
                || (fallbackEx instanceof ReceiptParseException parseEx && parseEx.isNoItemsFound());
    }

    private static String describe(RuntimeException ex) {
        return ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : " " + ex.getMessage());
    }

    private static String sourceUrlOf(String qrPayload) {
        var trimmed = qrPayload == null ? null : qrPayload.trim();
        return trimmed != null && trimmed.toLowerCase().startsWith("http") ? trimmed : null;
    }

    /**
     * Build a {@link FetchedDocument} from content the CLIENT fetched on-device
     * (its own residential/mobile IP), for portals that serve the nota to phones
     * but block our datacenter server — Pernambuco does exactly this (301s
     * {@code http}→{@code https:444} and then refuses our IP with {@code <erro>100</erro>}).
     * The app reads its OWN nota's SEFAZ page and posts the body; we sanitize the
     * CPF and verify the content actually carries the submitted chave — a client
     * must not be able to staple one nota's data onto another's key. Parsing then
     * runs through the same per-UF parser ({@link #parse}) as a server-side fetch.
     */
    public FetchedDocument fromClientContent(String rawContent, String chave, UnidadeFederativa uf, String sourceUrl) {
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        var sanitized = CpfMasker.strip(rawContent == null ? "" : rawContent);
        if (chave == null || !sanitized.contains(chave)) {
            log.warn("sefaz.client_content.chave_absent uf={} chave={}", uf, abbrev(chave));
            throw new ReceiptParseException("client-content-chave-mismatch");
        }
        log.info("sefaz.client_content.accepted uf={} chave={} bytes={}", uf, abbrev(chave), sanitized.length());
        return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl, null, true);
    }

    /**
     * Build a pre-parsed {@link FetchedDocument} from a raw NFe XML the user uploaded (e-commerce
     * import — Amazon/ML/Shopee). The XML is self-contained, so it's parsed DIRECTLY with
     * {@link NfceXmlParser} (no per-UF adapter, no SEFAZ fetch) after CPF stripping — which is why
     * this works for any state, unlike the reconsult path. The result rides as {@code preParsed}
     * so {@link #parse} returns it as-is.
     */
    public FetchedDocument fromXml(String xmlContent, String chave, String sourceUrl) {
        var sanitized = CpfMasker.strip(xmlContent == null ? "" : xmlContent);
        var uf = ChaveAcessoParser.extractUf(chave);
        var parsed = NfceXmlParser.parse(sanitized, chave, sourceUrl);
        log.info("sefaz.xml_upload.parsed uf={} chave={} items={}", uf, abbrev(chave), parsed.items().size());
        return new FetchedDocument(null, sanitized, chave, uf, sourceUrl, parsed);
    }

    /**
     * Re-runs parsing on already-stored HTML — used by the admin reparse
     * endpoint when a parser fix lands and we want to re-process old
     * receipts without hitting SEFAZ again. One exception to "no SEFAZ":
     * when the stored HTML is a portal REJECTION page, the RS bare-chave
     * arbiter is consulted (see {@link #rescueRejectionViaChaveReconsult}) —
     * that's how failed notas with a broken printed QR get rescued in place.
     * Callers must therefore NOT hold a DB transaction across this call.
     */
    public ParsedReceipt reparseStored(UnidadeFederativa uf, String html, String chave, String sourceUrl) {
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        try {
            return adapter.parseHtml(html, chave, sourceUrl);
        } catch (SefazPortalRejectionException rejectionEx) {
            var rescued = rescueRejectionViaChaveReconsult(new FetchedDocument(adapter, html, chave, uf, sourceUrl));
            if (rescued != null) return rescued;
            throw rejectionEx;
        }
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }

    /**
     * @param adapter    the adapter that produced {@code html}; null for Infosimples fallback results
     * @param html       sanitized DANFE HTML; null for Infosimples fallback results
     * @param preParsed  already-parsed receipt from Infosimples; null for normal HTML-based flow
     */
    /** {@code fromDevice}: the html was fetched by the user's app on-device, not by our server. */
    public record FetchedDocument(SefazAdapter adapter, String html, String chave,
                                  UnidadeFederativa uf, String sourceUrl, ParsedReceipt preParsed,
                                  boolean fromDevice) {
        public FetchedDocument(SefazAdapter adapter, String html, String chave,
                               UnidadeFederativa uf, String sourceUrl, ParsedReceipt preParsed) {
            this(adapter, html, chave, uf, sourceUrl, preParsed, false);
        }

        /** Backward-compatible constructor for existing code and tests. */
        public FetchedDocument(SefazAdapter adapter, String html, String chave,
                               UnidadeFederativa uf, String sourceUrl) {
            this(adapter, html, chave, uf, sourceUrl, null);
        }
    }
}
