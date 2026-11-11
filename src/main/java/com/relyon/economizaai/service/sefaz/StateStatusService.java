package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.dto.response.StateStatusResponse;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.StateHealthStatus;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.StateIngestionAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-state scanning situation shown in the in-app FAQ, derived from what the pipeline
 * already knows — never a hand-maintained list:
 * <ul>
 *   <li>{@link StateHealthStatus#VALIDATING} — the UF has no verified adapter (experimental
 *       catch-all, still collecting notas);</li>
 *   <li>{@link StateHealthStatus#UNSTABLE} — a verified UF that is on the paused-portal watch
 *       list, whose parser is failing in a burst (the regression signal), or whose recent
 *       notas are mostly failing for reasons that are not about the nota itself;</li>
 *   <li>{@link StateHealthStatus#WORKING} — every other verified UF.</li>
 * </ul>
 * The answer is the same for every user, so it is cached for a few minutes.
 */
@Slf4j
@Service
public class StateStatusService {

    private static final Set<ReceiptStatus> INGESTED_STATUSES = EnumSet.of(
            ReceiptStatus.PENDING_CONFIRMATION, ReceiptStatus.CONFIRMED, ReceiptStatus.REJECTED);

    private static final Map<UnidadeFederativa, String> STATE_NAMES = stateNames();

    private final SefazIngestionService sefazIngestionService;
    private final ReceiptRepository receiptRepository;
    private final StateIngestionAttemptRepository attemptRepository;
    private final Set<UnidadeFederativa> pausedStates;
    private final int windowHours;
    private final int minFailures;
    private final int regressionMinFailures;
    private final long cacheSeconds;
    private Clock clock = Clock.systemUTC();
    private volatile CachedStatus cached;

    public StateStatusService(
            SefazIngestionService sefazIngestionService,
            ReceiptRepository receiptRepository,
            StateIngestionAttemptRepository attemptRepository,
            @Value("${economizaai.ingestion.paused-state-probe.ufs:SE}") String pausedStatesCsv,
            @Value("${economizaai.ingestion.state-status.window-hours:24}") int windowHours,
            @Value("${economizaai.ingestion.state-status.min-failures:3}") int minFailures,
            @Value("${economizaai.ingestion.sefaz.regression.min-failures:5}") int regressionMinFailures,
            @Value("${economizaai.ingestion.state-status.cache-seconds:300}") long cacheSeconds) {
        this.sefazIngestionService = sefazIngestionService;
        this.receiptRepository = receiptRepository;
        this.attemptRepository = attemptRepository;
        this.pausedStates = parseStates(pausedStatesCsv);
        this.windowHours = Math.max(1, windowHours);
        this.minFailures = Math.max(1, minFailures);
        this.regressionMinFailures = Math.max(1, regressionMinFailures);
        this.cacheSeconds = Math.max(0, cacheSeconds);
    }

    /** Test seam — drives the cache expiry deterministically. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /** The 27 UFs, ordered by code, each with its current status. Cached for {@code cache-seconds}. */
    public List<StateStatusResponse> currentStatus() {
        var now = clock.instant();
        var snapshot = cached;
        if (snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return snapshot.states();
        }
        try {
            var fresh = statusOfEveryState(recentlyFailingStates());
            cached = new CachedStatus(fresh, now.plusSeconds(cacheSeconds));
            log.info("state_status.refreshed unstable={} validating={}",
                    count(fresh, StateHealthStatus.UNSTABLE), count(fresh, StateHealthStatus.VALIDATING));
            return fresh;
        } catch (RuntimeException telemetryFailure) {
            // The FAQ must not break because the health numbers could not be read: answer from
            // the adapters alone (not cached, so the next request tries the numbers again).
            log.warn("state_status.telemetry_unavailable reason={}", telemetryFailure.getClass().getSimpleName());
            return snapshot != null ? snapshot.states() : statusOfEveryState(EnumSet.noneOf(UnidadeFederativa.class));
        }
    }

    private List<StateStatusResponse> statusOfEveryState(Set<UnidadeFederativa> recentlyFailing) {
        var verified = sefazIngestionService.getVerifiedStates();
        return Arrays.stream(UnidadeFederativa.values())
                .sorted(Comparator.comparing(Enum::name))
                .map(uf -> new StateStatusResponse(uf.name(), STATE_NAMES.get(uf),
                        statusOf(uf, verified, recentlyFailing)))
                .toList();
    }

    private StateHealthStatus statusOf(UnidadeFederativa uf, Set<UnidadeFederativa> verified,
                                       Set<UnidadeFederativa> recentlyFailing) {
        if (!verified.contains(uf)) return StateHealthStatus.VALIDATING;
        if (pausedStates.contains(uf) || recentlyFailing.contains(uf)) return StateHealthStatus.UNSTABLE;
        return StateHealthStatus.WORKING;
    }

    /** UFs whose recent notas are mostly failing, or whose verified parser is failing in a burst. */
    private Set<UnidadeFederativa> recentlyFailingStates() {
        var failing = EnumSet.noneOf(UnidadeFederativa.class);
        failing.addAll(statesWithMostlyFailingNotas());
        failing.addAll(statesWithParserRegression());
        return failing;
    }

    private Set<UnidadeFederativa> statesWithMostlyFailingNotas() {
        var since = LocalDateTime.now(clock).minusHours(windowHours);
        var ingested = new EnumMap<UnidadeFederativa, Long>(UnidadeFederativa.class);
        var failed = new EnumMap<UnidadeFederativa, Long>(UnidadeFederativa.class);
        for (var outcome : receiptRepository.summarizeByUfAndStatusSince(since)) {
            if (INGESTED_STATUSES.contains(outcome.getStatus())) {
                ingested.merge(outcome.getUf(), outcome.getNotas(), Long::sum);
            } else if (outcome.getStatus() == ReceiptStatus.PORTAL_PENDING) {
                failed.merge(outcome.getUf(), outcome.getNotas(), Long::sum);
            }
        }
        // A failure that is about the nota itself (typo, nota not found, unsupported merchant)
        // says nothing about the state's portal — only the other failures count.
        for (var failure : receiptRepository.summarizeFailureReasonsSince(since)) {
            if (!ReceiptRetryPolicy.isDeterministicFailure(failure.getReason())) {
                failed.merge(failure.getUf(), failure.getNotas(), Long::sum);
            }
        }
        var mostlyFailing = EnumSet.noneOf(UnidadeFederativa.class);
        failed.forEach((uf, failures) -> {
            if (failures >= minFailures && failures > ingested.getOrDefault(uf, 0L)) {
                mostlyFailing.add(uf);
            }
        });
        return mostlyFailing;
    }

    private Set<UnidadeFederativa> statesWithParserRegression() {
        var since = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minusHours(windowHours);
        var parseFailures = new EnumMap<UnidadeFederativa, Long>(UnidadeFederativa.class);
        for (var layer : attemptRepository.summarizeSince(since)) {
            if (layer.getStrategy() == StateIngestionStrategy.VERIFIED_ADAPTER
                    && layer.getOutcome() == StateIngestionOutcome.PARSE_FAILED) {
                parseFailures.merge(layer.getUf(), layer.getAttempts(), Long::sum);
            }
        }
        var regressed = EnumSet.noneOf(UnidadeFederativa.class);
        parseFailures.forEach((uf, failures) -> {
            if (failures >= regressionMinFailures) regressed.add(uf);
        });
        return regressed;
    }

    private static long count(List<StateStatusResponse> states, StateHealthStatus status) {
        return states.stream().filter(state -> state.status() == status).count();
    }

    private static Set<UnidadeFederativa> parseStates(String csv) {
        var states = EnumSet.noneOf(UnidadeFederativa.class);
        if (csv == null || csv.isBlank()) return states;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    try {
                        states.add(UnidadeFederativa.valueOf(token.toUpperCase()));
                    } catch (IllegalArgumentException unknownUf) {
                        log.warn("state_status.paused_states.ignored token='{}'", token);
                    }
                });
        return states;
    }

    private static Map<UnidadeFederativa, String> stateNames() {
        var names = new EnumMap<UnidadeFederativa, String>(UnidadeFederativa.class);
        names.put(UnidadeFederativa.AC, "Acre");
        names.put(UnidadeFederativa.AL, "Alagoas");
        names.put(UnidadeFederativa.AM, "Amazonas");
        names.put(UnidadeFederativa.AP, "Amapá");
        names.put(UnidadeFederativa.BA, "Bahia");
        names.put(UnidadeFederativa.CE, "Ceará");
        names.put(UnidadeFederativa.DF, "Distrito Federal");
        names.put(UnidadeFederativa.ES, "Espírito Santo");
        names.put(UnidadeFederativa.GO, "Goiás");
        names.put(UnidadeFederativa.MA, "Maranhão");
        names.put(UnidadeFederativa.MG, "Minas Gerais");
        names.put(UnidadeFederativa.MS, "Mato Grosso do Sul");
        names.put(UnidadeFederativa.MT, "Mato Grosso");
        names.put(UnidadeFederativa.PA, "Pará");
        names.put(UnidadeFederativa.PB, "Paraíba");
        names.put(UnidadeFederativa.PE, "Pernambuco");
        names.put(UnidadeFederativa.PI, "Piauí");
        names.put(UnidadeFederativa.PR, "Paraná");
        names.put(UnidadeFederativa.RJ, "Rio de Janeiro");
        names.put(UnidadeFederativa.RN, "Rio Grande do Norte");
        names.put(UnidadeFederativa.RO, "Rondônia");
        names.put(UnidadeFederativa.RR, "Roraima");
        names.put(UnidadeFederativa.RS, "Rio Grande do Sul");
        names.put(UnidadeFederativa.SC, "Santa Catarina");
        names.put(UnidadeFederativa.SE, "Sergipe");
        names.put(UnidadeFederativa.SP, "São Paulo");
        names.put(UnidadeFederativa.TO, "Tocantins");
        return names;
    }

    private record CachedStatus(List<StateStatusResponse> states, Instant expiresAt) {}
}
