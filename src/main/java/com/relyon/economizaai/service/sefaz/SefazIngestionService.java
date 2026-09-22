package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.ExperimentalPortalEvidence;
import com.relyon.economizaai.exception.ExperimentalStateFailedException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.PaidApiUnavailableException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.exception.UnsupportedStateException;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class SefazIngestionService {

    private final Map<UnidadeFederativa, SefazAdapter> adapters;
    private final Set<UnidadeFederativa> verifiedStates;
    private final Optional<InfosimplesService> infosimples;
    private final PaidApiGuardService paidApiGuard;
    private final StateCoverageService stateCoverage;

    public SefazIngestionService(List<SefazAdapter> adapters,
                                 Optional<InfosimplesService> infosimples,
                                 PaidApiGuardService paidApiGuard,
                                 StateCoverageService stateCoverage) {
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

    public ParsedReceipt ingest(String qrPayload) {
        var fetched = fetch(qrPayload);
        return parse(fetched);
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
     * Whether a bare 44-digit chave (manual entry — no QR signature) can be
     * ingested for this UF. RS is a hard no (its SEFAZ needs a gov.br login to
     * consult by chave; not even Infosimples rescues it). Portals that require
     * the QR signature are served by the paid by-chave fallback when enabled.
     */
    public boolean supportsBareChave(UnidadeFederativa uf) {
        if (uf == UnidadeFederativa.RS) return false;
        var adapter = adapters.get(uf);
        if (adapter == null) return false;
        return !adapter.requiresQrSignature() || infosimples.isPresent();
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
     */
    public FetchedDocument fetch(String qrPayload) {
        return fetch(qrPayload, null);
    }

    /**
     * As {@link #fetch(String)}, but attributes the paid calls it makes (captcha
     * solves, Infosimples queries) to {@code userId} for the per-user daily cap
     * and the reconciliation ledger. A null {@code userId} (admin reparse, tests)
     * skips the cap but is still logged.
     */
    public FetchedDocument fetch(String qrPayload, UUID userId) {
        var chave = resolveChave(qrPayload);
        var uf = ChaveAcessoParser.extractUf(chave);
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        // A manually-typed bare chave (damaged QR) has no signature. Portals that
        // need it (SVRS family — PR/SP) can't be scraped, so go straight to the
        // Infosimples by-chave fallback instead of a doomed scrape. RS is rejected
        // up front at submit (gov.br wall), so it doesn't reach here.
        if (adapter.requiresQrSignature() && ChaveAcessoParser.isBareChave(qrPayload)) {
            if (infosimples.isEmpty()) {
                log.warn("sefaz.fetch.bare_chave uf={} chave={} — no by-chave fallback configured", uf, abbrev(chave));
                throw new SefazFetchException(uf.name());
            }
            log.info("sefaz.fetch.bare_chave uf={} chave={} — routing to infosimples (no QR signature to scrape)", uf, abbrev(chave));
            var rescued = fetchViaInfosimples(chave, uf, userId);
            if (isExperimental(adapter)) {
                stateCoverage.recordSuccess(uf, StateIngestionStrategy.INFOSIMPLES, null);
            }
            return rescued;
        }
        // Every scrape solves at least one captcha (paid). Global kill-switch first,
        // then the per-user cap, before spending.
        paidApiGuard.assertUnderGlobalBudget();
        paidApiGuard.assertWithinDailyCap(userId, PaidApiService.CAPTCHA_SOLVE);
        try {
            var html = adapter.fetchHtml(qrPayload);
            paidApiGuard.recordSuccess(userId, PaidApiService.CAPTCHA_SOLVE, uf.name(), null);
            var sanitized = CpfMasker.strip(html);
            var sourceUrl = qrPayload.trim().toLowerCase().startsWith("http") ? qrPayload.trim() : null;
            return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl, null);
        } catch (SefazFetchException | CaptchaUnavailableException | CaptchaSolveFailedException primaryEx) {
            // A CaptchaUnavailableException means no solver was configured — no money
            // was spent, so don't record a captcha cost. Any other failure here means
            // a solve was attempted (and billed) but the portal still failed.
            if (!(primaryEx instanceof CaptchaUnavailableException)) {
                paidApiGuard.recordFailure(userId, PaidApiService.CAPTCHA_SOLVE, uf.name(), null);
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
            if (infosimples.isEmpty()) {
                if (experimental) {
                    throw experimentalExhausted(uf, chave, sourceUrlOf(qrPayload),
                            "QR_PORTAL: " + describe(primaryEx) + "; INFOSIMPLES: desabilitado", portalEvidence);
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
                    throw experimentalExhausted(uf, chave, sourceUrlOf(qrPayload),
                            "QR_PORTAL: " + describe(primaryEx) + "; INFOSIMPLES: " + describe(fallbackEx),
                            portalEvidence);
                }
                throw fallbackEx;
            }
        }
    }

    private static String joinDetail(String description, String portalEvidence) {
        return portalEvidence == null ? description : description + "\n" + portalEvidence;
    }

    /**
     * The paid by-chave fallback. Guarded by the Infosimples circuit breaker (skip
     * when the provider is failing) and the per-user daily cap, and every attempt —
     * success or failure — is written to the ledger. A failure trips the breaker.
     */
    private FetchedDocument fetchViaInfosimples(String chave, UnidadeFederativa uf, UUID userId) {
        paidApiGuard.assertUnderGlobalBudget();
        paidApiGuard.assertCircuitClosed(PaidApiService.INFOSIMPLES);
        paidApiGuard.assertWithinDailyCap(userId, PaidApiService.INFOSIMPLES);
        try {
            var preParsed = infosimples.get().fetchParsed(chave, uf);
            paidApiGuard.recordSuccess(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples");
            log.info("sefaz.fetch.infosimples.ok uf={} chave={} items={}",
                    uf, abbrev(chave), preParsed.items().size());
            return new FetchedDocument(null, null, chave, uf, preParsed.sourceUrl(), preParsed);
        } catch (RuntimeException ex) {
            paidApiGuard.recordFailure(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples");
            throw ex;
        }
    }

    /**
     * Step 2: parse the fetched HTML. Returns the pre-parsed receipt immediately
     * when the fetch was served by the Infosimples fallback.
     */
    public ParsedReceipt parse(FetchedDocument fetched) {
        return parse(fetched, null);
    }

    /**
     * As {@link #parse(FetchedDocument)}, attributing any paid rescue to
     * {@code userId}. For experimental states an unparseable layout (portal
     * responded but not with the responsive DANFE) is retried via Infosimples
     * before giving up — the last chain layer.
     */
    public ParsedReceipt parse(FetchedDocument fetched, UUID userId) {
        if (fetched.preParsed() != null) return fetched.preParsed();
        var experimental = isExperimental(fetched.adapter());
        var qrHost = StateCoverageService.hostOf(fetched.sourceUrl());
        try {
            var parsed = fetched.adapter().parseHtml(fetched.html(), fetched.chave(), fetched.sourceUrl());
            if (experimental) {
                stateCoverage.recordSuccess(fetched.uf(), StateIngestionStrategy.QR_PORTAL, qrHost);
            }
            return parsed;
        } catch (ReceiptParseException parseEx) {
            if (!experimental) {
                // A verified state's parser failed on real HTML — the regression
                // signature (portal changed its DANFE format). Record it; a burst
                // of these alerts the admin with the HTML to fix the adapter.
                stateCoverage.recordVerifiedParseFailure(fetched.uf(), fetched.chave(), fetched.sourceUrl(),
                        parseEx.getMessageKey() + ":" + String.join(",", parseEx.getArguments()), fetched.html());
                throw parseEx;
            }
            stateCoverage.recordFailure(fetched.uf(), StateIngestionStrategy.QR_PORTAL,
                    StateIngestionOutcome.PARSE_FAILED, qrHost, describe(parseEx));
            var infosimplesNote = "desabilitado";
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
                }
            }
            throw experimentalExhausted(fetched.uf(), fetched.chave(), fetched.sourceUrl(),
                    "QR_PORTAL (parse): " + describe(parseEx) + "; INFOSIMPLES: " + infosimplesNote,
                    fetched.html());
        }
    }

    /**
     * Terminal failure of the experimental chain: record + alert the admin with
     * the evidence, and surface the user-facing "not supported yet" key.
     */
    private ExperimentalStateFailedException experimentalExhausted(UnidadeFederativa uf, String chave,
                                                                   String sourceUrl, String failureSummary,
                                                                   String htmlSnippet) {
        stateCoverage.reportExhausted(uf, chave, sourceUrl, failureSummary, htmlSnippet);
        return new ExperimentalStateFailedException(uf.name());
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
        return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl);
    }

    /**
     * Re-runs parsing on already-stored HTML — used by the admin reparse
     * endpoint when a parser fix lands and we want to re-process old
     * receipts without hitting SEFAZ again.
     */
    public ParsedReceipt reparseStored(UnidadeFederativa uf, String html, String chave, String sourceUrl) {
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        return adapter.parseHtml(html, chave, sourceUrl);
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }

    /**
     * @param adapter    the adapter that produced {@code html}; null for Infosimples fallback results
     * @param html       sanitized DANFE HTML; null for Infosimples fallback results
     * @param preParsed  already-parsed receipt from Infosimples; null for normal HTML-based flow
     */
    public record FetchedDocument(SefazAdapter adapter, String html, String chave,
                                  UnidadeFederativa uf, String sourceUrl, ParsedReceipt preParsed) {
        /** Backward-compatible constructor for existing code and tests. */
        public FetchedDocument(SefazAdapter adapter, String html, String chave,
                               UnidadeFederativa uf, String sourceUrl) {
            this(adapter, html, chave, uf, sourceUrl, null);
        }
    }
}
