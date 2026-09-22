package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.dto.response.StateCoverageResponse;
import com.relyon.economizaai.dto.response.StateCoverageResponse.StateCoverageEntry;
import com.relyon.economizaai.dto.response.StateCoverageResponse.StateCoverageEntry.StrategyStats;
import com.relyon.economizaai.model.StateIngestionAttempt;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.StateIngestionAttemptRepository;
import com.relyon.economizaai.service.ContactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * On-demand learning for the experimental multi-state rollout: records every
 * fallback-chain layer attempt on a non-verified UF, alerts the admin inbox on
 * the two events that need a human — the FIRST success on a new state (grab a
 * fixture from the receipt's rawHtml, promote the UF to a verified adapter)
 * and total chain failure (build support; the email carries the evidence).
 *
 * <p>Also watches VERIFIED states for REGRESSION: a portal we already support
 * can change its HTML format and silently start failing. When a verified UF's
 * parser fails on enough distinct receipts in a short window, the admin is
 * alerted with the raw HTML so the adapter can be fixed (see
 * {@link #recordVerifiedParseFailure}).
 *
 * <p>All methods are best-effort: telemetry or an alert must never break an
 * otherwise-recoverable ingest. Failure alerts are deduped to one per UF per
 * day (UTC) via the {@code admin_notified} flag on the EXHAUSTED row.
 */
@Slf4j
@Service
public class StateCoverageService {

    private static final int DETAIL_MAX_CHARS = 2000;
    private static final int SNIPPET_MAX_CHARS = 1200;
    private static final Pattern URL_HOST = Pattern.compile(
            "^https?://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);

    private final StateIngestionAttemptRepository repository;
    private final ContactService contactService;
    private final int regressionMinFailures;
    private final long regressionWindowHours;

    public StateCoverageService(
            StateIngestionAttemptRepository repository,
            ContactService contactService,
            @Value("${economizaai.ingestion.sefaz.regression.min-failures:5}") int regressionMinFailures,
            @Value("${economizaai.ingestion.sefaz.regression.window-hours:6}") long regressionWindowHours) {
        this.repository = repository;
        this.contactService = contactService;
        this.regressionMinFailures = regressionMinFailures;
        this.regressionWindowHours = regressionWindowHours;
    }

    /** Records a successful layer; the first-ever success for the UF alerts the admin. */
    public void recordSuccess(UnidadeFederativa uf, StateIngestionStrategy strategy, String qrHost) {
        try {
            var firstSuccessForUf = !repository.existsByUfAndOutcome(uf, StateIngestionOutcome.SUCCESS);
            persist(uf, strategy, StateIngestionOutcome.SUCCESS, qrHost, null, false);
            log.info("state_coverage.success uf={} strategy={} host={} first={}", uf, strategy, qrHost, firstSuccessForUf);
            if (firstSuccessForUf) {
                contactService.notifyAdmin(
                        "nova UF validada: " + uf + " via " + strategy,
                        """
                        Primeira nota de %s ingerida com sucesso pela camada %s (host: %s).

                        Próximos passos para promover a UF a "verificada":
                        1. Confira os itens/total da nota no app (validação por usuário real).
                        2. Salve o rawHtml do receipt como fixture em src/test/resources/fixtures/sefaz/%s/.
                        3. Se a camada foi QR_PORTAL, adicione %s a economizaai.ingestion.sefaz.svrs.states \
                        e o host à allowlist — a UF passa a usar o adapter verificado.
                        """.formatted(uf, strategy, qrHost, uf.name().toLowerCase(), uf));
            }
        } catch (RuntimeException ex) {
            log.warn("state_coverage.record_failed uf={} reason={}", uf, ex.getClass().getSimpleName());
        }
    }

    /** Records a failed layer attempt (fetch or parse) with its evidence. */
    public void recordFailure(UnidadeFederativa uf, StateIngestionStrategy strategy,
                              StateIngestionOutcome outcome, String qrHost, String detail) {
        try {
            persist(uf, strategy, outcome, qrHost, truncate(detail, DETAIL_MAX_CHARS), false);
            log.info("state_coverage.failure uf={} strategy={} outcome={}", uf, strategy, outcome);
        } catch (RuntimeException ex) {
            log.warn("state_coverage.record_failed uf={} reason={}", uf, ex.getClass().getSimpleName());
        }
    }

    /**
     * A VERIFIED state's parser failed on a fetched DANFE — the signature of a
     * portal that changed its HTML format. Records the failure, and once enough
     * DISTINCT-receipt failures pile up in the regression window (default 5 in
     * 6h) alerts the admin ONCE per UF per day with the raw HTML so the adapter
     * can be fixed. A single parse failure is normal noise (aged-out nota, bad
     * scan); a burst on a state we already support is the real signal.
     */
    public void recordVerifiedParseFailure(UnidadeFederativa uf, String chave, String sourceUrl,
                                           String reason, String htmlSnippet) {
        try {
            var detail = reason + (htmlSnippet == null ? "" : "\n---\n" + truncate(htmlSnippet, SNIPPET_MAX_CHARS));
            persist(uf, StateIngestionStrategy.VERIFIED_ADAPTER, StateIngestionOutcome.PARSE_FAILED,
                    hostOf(sourceUrl), truncate(detail, DETAIL_MAX_CHARS), false);
            var windowStart = OffsetDateTime.now(ZoneOffset.UTC).minusHours(regressionWindowHours);
            var recentFailures = repository.countByUfAndStrategyAndOutcomeAndCreatedAtGreaterThanEqual(
                    uf, StateIngestionStrategy.VERIFIED_ADAPTER, StateIngestionOutcome.PARSE_FAILED, windowStart);
            log.warn("state_coverage.verified_parse_failed uf={} recent={}/{} reason={}",
                    uf, recentFailures, regressionMinFailures, reason);
            if (recentFailures < regressionMinFailures) return;
            if (repository.existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(uf, startOfTodayUtc())) return;
            persist(uf, StateIngestionStrategy.VERIFIED_ADAPTER, StateIngestionOutcome.PARSE_FAILED,
                    hostOf(sourceUrl), "regression alert marker", true);
            contactService.notifyAdmin(
                    "REGRESSÃO de portal: " + uf + " parou de parsear",
                    """
                    O adapter VERIFICADO de %s falhou ao parsear %d+ notas nas últimas %dh. \
                    Provavelmente o portal mudou o layout do DANFE e o parser precisa de ajuste.

                    - chave (amostra): %s
                    - URL: %s
                    - Motivo do parser: %s
                    - Ação: atualize o adapter/parser da UF (ou crie um novo) e regrave a fixture \
                    em src/test/resources/fixtures/sefaz/%s/ com este HTML.

                    Primeiros %d chars do HTML que não parseou mais (CPF já removido):
                    %s
                    """.formatted(uf, regressionMinFailures, regressionWindowHours, chave,
                            sourceUrl == null ? "(sem URL)" : sourceUrl, reason, uf.name().toLowerCase(),
                            SNIPPET_MAX_CHARS, htmlSnippet == null ? "(sem HTML)" : truncate(htmlSnippet, SNIPPET_MAX_CHARS)));
        } catch (RuntimeException ex) {
            log.warn("state_coverage.verified_regression_failed uf={} reason={}", uf, ex.getClass().getSimpleName());
        }
    }

    /**
     * Every layer failed for this receipt. Records the terminal EXHAUSTED row and
     * emails the admin everything needed to implement the state — at most once per
     * UF per day, so a broken portal doesn't flood the inbox.
     */
    public void reportExhausted(UnidadeFederativa uf, String chave, String sourceUrl,
                                String failureSummary, String htmlSnippet) {
        try {
            var alreadyNotifiedToday = repository.existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(
                    uf, startOfTodayUtc());
            var detail = failureSummary + (htmlSnippet == null ? "" : "\n---\n" + truncate(htmlSnippet, SNIPPET_MAX_CHARS));
            persist(uf, StateIngestionStrategy.QR_PORTAL, StateIngestionOutcome.EXHAUSTED,
                    hostOf(sourceUrl), truncate(detail, DETAIL_MAX_CHARS), !alreadyNotifiedToday);
            log.warn("state_coverage.exhausted uf={} notified={}", uf, !alreadyNotifiedToday);
            if (alreadyNotifiedToday) return;
            contactService.notifyAdmin(
                    "UF sem suporte: " + uf + " falhou em todas as camadas",
                    """
                    Um usuário escaneou uma NFC-e de %s e nenhuma camada conseguiu processá-la. \
                    O usuário recebeu "ainda não suportamos esse estado".

                    Evidências para implementar o suporte:
                    - chave: %s
                    - URL do QR: %s
                    - Camadas tentadas: %s
                    - Receipt FAILED_PARSE com este chave guarda o rawHtml (quando o fetch funcionou).
                    - Recon do portal: docs/MULTI_STATE_RECON.md (tier da UF + notas do probe).

                    Primeiros %d chars da resposta do portal (CPF já removido):
                    %s
                    """.formatted(uf, chave, sourceUrl == null ? "(bare chave)" : sourceUrl, failureSummary,
                            SNIPPET_MAX_CHARS, htmlSnippet == null ? "(sem resposta)" : truncate(htmlSnippet, SNIPPET_MAX_CHARS)));
        } catch (RuntimeException ex) {
            log.warn("state_coverage.exhausted_report_failed uf={} reason={}", uf, ex.getClass().getSimpleName());
        }
    }

    /**
     * The full 27-UF coverage map: mode per UF plus per-strategy telemetry from
     * real users' scans. The caller (AdminController) supplies the mode sets so
     * this service never depends back on {@code SefazIngestionService}.
     */
    public StateCoverageResponse report(Set<UnidadeFederativa> verified, Set<UnidadeFederativa> experimental) {
        var summariesByUf = new HashMap<UnidadeFederativa, List<StateIngestionAttemptRepository.StateAttemptSummary>>();
        for (var summary : repository.summarize()) {
            summariesByUf.computeIfAbsent(summary.getUf(), key -> new ArrayList<>()).add(summary);
        }
        var entries = Arrays.stream(UnidadeFederativa.values())
                .map(uf -> toEntry(uf, modeOf(uf, verified, experimental), summariesByUf.getOrDefault(uf, List.of())))
                .sorted(Comparator.comparing(entry -> entry.uf().name()))
                .toList();
        return new StateCoverageResponse(entries);
    }

    private static String modeOf(UnidadeFederativa uf, Set<UnidadeFederativa> verified,
                                 Set<UnidadeFederativa> experimental) {
        if (verified.contains(uf)) return "VERIFIED";
        if (experimental.contains(uf)) return "EXPERIMENTAL";
        return "UNSUPPORTED";
    }

    private static StateCoverageEntry toEntry(UnidadeFederativa uf, String mode,
                                              List<StateIngestionAttemptRepository.StateAttemptSummary> summaries) {
        long attempts = 0;
        long successes = 0;
        OffsetDateTime lastAttemptAt = null;
        var strategies = new HashMap<String, long[]>();
        for (var summary : summaries) {
            attempts += summary.getAttempts();
            var stats = strategies.computeIfAbsent(summary.getStrategy().name(), key -> new long[2]);
            if (summary.getOutcome() == StateIngestionOutcome.SUCCESS) {
                successes += summary.getAttempts();
                stats[0] += summary.getAttempts();
            } else {
                stats[1] += summary.getAttempts();
            }
            if (lastAttemptAt == null || summary.getLastAttemptAt().isAfter(lastAttemptAt)) {
                lastAttemptAt = summary.getLastAttemptAt();
            }
        }
        var strategyStats = new HashMap<String, StrategyStats>();
        strategies.forEach((strategy, counts) -> strategyStats.put(strategy, new StrategyStats(counts[0], counts[1])));
        return new StateCoverageEntry(uf, mode, attempts, successes, attempts - successes, lastAttemptAt,
                Map.copyOf(strategyStats));
    }

    private void persist(UnidadeFederativa uf, StateIngestionStrategy strategy, StateIngestionOutcome outcome,
                         String qrHost, String detail, boolean adminNotified) {
        repository.save(StateIngestionAttempt.builder()
                .uf(uf)
                .strategy(strategy)
                .outcome(outcome)
                .qrHost(qrHost)
                .detail(detail)
                .adminNotified(adminNotified)
                .build());
    }

    static String hostOf(String url) {
        if (url == null) return null;
        var matcher = URL_HOST.matcher(url.trim());
        return matcher.find() ? matcher.group(1).toLowerCase() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static OffsetDateTime startOfTodayUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
