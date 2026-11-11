package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.PaidApiUnavailableException;
import com.relyon.economizaai.model.PaidApiCall;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single gating point for money-spending external calls (Infosimples, captcha
 * solvers, Twilio, LLM). Responsibilities, matching the cost-control plan:
 *
 * <ul>
 *   <li><b>Budget:</b> {@link #assertUnderDailyBudget} stops ONE service once its
 *       daily R$ ceiling is spent, leaving the others (and free scraping) alone.</li>
 *   <li><b>Cap (A):</b> {@link #assertWithinDailyCap} throws before a user
 *       exceeds their per-day allowance for a service — a hard cost/abuse guard
 *       independent of subscription tier.</li>
 *   <li><b>Circuit breaker (B):</b> {@link #assertCircuitClosed} fails fast when
 *       a service has failed repeatedly in a short window, so we stop paying for
 *       calls that are going to fail while the provider is down.</li>
 *   <li><b>Ledger (C):</b> {@link #recordSuccess}/{@link #recordFailure} write one
 *       {@code paid_api_call} row per attempt for invoice reconciliation.</li>
 * </ul>
 *
 * <p>Called from the UNTRANSACTED ingestion phase — the record write opens its
 * own short transaction and never sits inside the SEFAZ/HTTP call.
 */
@Slf4j
@Service
public class PaidApiGuardService {

    private final PaidApiGuardProperties properties;
    private final PaidApiCallRepository repository;
    private Clock clock = Clock.systemUTC();

    /**
     * Recent failure instants per breaker key, pruned to the rolling window on each record.
     * The key is the service, plus the UF for Infosimples (see {@link #breakerKey}).
     */
    private final Map<String, Deque<Instant>> recentFailures = new ConcurrentHashMap<>();
    /** When the breaker (if open) reopens the circuit for a breaker key. */
    private final Map<String, Instant> openUntil = new ConcurrentHashMap<>();

    public PaidApiGuardService(PaidApiGuardProperties properties, PaidApiCallRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    /** Test seam — override the clock to drive the breaker window/cooldown deterministically. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Throws {@link PaidApiQuotaExceededException} if the user has already spent
     * their day's allowance for this service. No-op when enforcement is off, the
     * user is unknown (internal reparse/admin), or the cap is unlimited.
     */
    public void assertWithinDailyCap(UUID userId, PaidApiService service) {
        if (!properties.isEnabled() || userId == null) return;
        var cap = dailyCap(service);
        if (cap <= 0) return;
        var used = repository.countByUserIdAndServiceAndCreatedAtGreaterThanEqual(userId, service, startOfTodayUtc());
        if (used >= cap) {
            log.warn("paid_api.cap_exceeded service={} user={} used={} cap={}",
                    service, abbrev(userId), used, cap);
            throw new PaidApiQuotaExceededException(service.name());
        }
    }

    /**
     * Daily cap for a SYSTEM worker (no user attribution) — counts all of today's
     * calls for the service. Used by the LLM enrichment/auditor batch jobs.
     */
    public void assertWithinServiceDailyCap(PaidApiService service) {
        if (!properties.isEnabled()) return;
        var cap = dailyCap(service);
        if (cap <= 0) return;
        var today = repository.countByServiceAndCreatedAtGreaterThanEqual(service, startOfTodayUtc());
        if (today >= cap) {
            log.warn("paid_api.service_cap_hit service={} today={} cap={}", service, today, cap);
            throw new PaidApiQuotaExceededException(service.name());
        }
    }

    /**
     * Throws {@link PaidApiBudgetExceededException} once today's estimated spend for
     * THIS service reached its daily ceiling (or the optional overall ceiling is gone).
     * Only this service stops — the others keep working. No-op when enforcement is off
     * or the budget is unlimited.
     */
    public void assertUnderDailyBudget(PaidApiService service) {
        if (isDailyBudgetExhausted(service)) {
            throw new PaidApiBudgetExceededException();
        }
    }

    /**
     * Whether today's estimated spend for this service already reached its ceiling. For
     * callers that must keep their FREE path working when the budget is gone (a
     * captcha-free scrape costs nothing) and only refuse the paid step.
     */
    public boolean isDailyBudgetExhausted(PaidApiService service) {
        if (!properties.isEnabled()) return false;
        return isServiceBudgetExhausted(service) || isOverallBudgetExhausted();
    }

    private boolean isServiceBudgetExhausted(PaidApiService service) {
        var budget = properties.dailyBudgetCents(service);
        if (budget <= 0) return false;
        var spent = repository.sumCostCentsByServiceSince(service, startOfTodayUtc());
        if (spent >= budget) {
            log.warn("paid_api.budget_exhausted service={} spentCents={} budgetCents={}", service, spent, budget);
            return true;
        }
        return false;
    }

    private boolean isOverallBudgetExhausted() {
        var budget = properties.getDailyGlobalBudgetCents();
        if (budget <= 0) return false;
        var spent = repository.sumCostCentsSince(startOfTodayUtc());
        if (spent >= budget) {
            log.warn("paid_api.budget_exhausted service=ALL spentCents={} budgetCents={}", spent, budget);
            return true;
        }
        return false;
    }

    /**
     * Throws {@link PaidApiUnavailableException} while the breaker for this
     * service is open. No-op when enforcement is off.
     */
    public void assertCircuitClosed(PaidApiService service) {
        assertCircuitClosed(service, null);
    }

    /**
     * As {@link #assertCircuitClosed(PaidApiService)} but scoped to one UF. Infosimples reaches
     * each state through that state's own portal, so one state's outage must only pause the
     * paid calls for that state — the others keep working.
     */
    public void assertCircuitClosed(PaidApiService service, String uf) {
        if (!properties.isEnabled()) return;
        var until = openUntil.get(breakerKey(service, uf));
        if (until != null && until.isAfter(clock.instant())) {
            log.warn("paid_api.circuit_open service={} uf={} reopensAt={}", service, uf, until);
            throw new PaidApiUnavailableException(service.name());
        }
    }

    /** Infosimples breaker state is per (service, UF); every other service keeps one breaker. */
    private static String breakerKey(PaidApiService service, String uf) {
        return service == PaidApiService.INFOSIMPLES && uf != null && !uf.isBlank()
                ? service.name() + ":" + uf
                : service.name();
    }

    /** Records a successful paid call and closes the breaker for the service. */
    public void recordSuccess(UUID userId, PaidApiService service, String uf, String provider) {
        recordSuccess(userId, service, uf, provider, null);
    }

    /** As {@link #recordSuccess(UUID, PaidApiService, String, String)} but with the provider result code. */
    public void recordSuccess(UUID userId, PaidApiService service, String uf, String provider, Integer resultCode) {
        var breakerKey = breakerKey(service, uf);
        recentFailures.remove(breakerKey);
        openUntil.remove(breakerKey);
        persist(userId, service, uf, provider, true, resultCode);
    }

    /** Records a failed paid call and trips the breaker if failures cross the threshold. */
    public void recordFailure(UUID userId, PaidApiService service, String uf, String provider) {
        recordFailure(userId, service, uf, provider, null);
    }

    /** As {@link #recordFailure(UUID, PaidApiService, String, String)} but with the provider result code. */
    public void recordFailure(UUID userId, PaidApiService service, String uf, String provider, Integer resultCode) {
        persist(userId, service, uf, provider, false, resultCode);
        registerFailureAndMaybeTrip(service, breakerKey(service, uf));
    }

    /**
     * Records a DETERMINISTIC failed call (the provider answered fine, the answer is just
     * "no data for this input") in the ledger WITHOUT counting it toward the breaker — a
     * retry batch of dead notas must never trip the circuit and starve recoverable ones.
     */
    public void recordDeterministicFailure(UUID userId, PaidApiService service, String uf, String provider) {
        recordDeterministicFailure(userId, service, uf, provider, null);
    }

    /** As {@link #recordDeterministicFailure(UUID, PaidApiService, String, String)} but with the provider result code. */
    public void recordDeterministicFailure(UUID userId, PaidApiService service, String uf, String provider, Integer resultCode) {
        persist(userId, service, uf, provider, false, resultCode);
    }

    private void registerFailureAndMaybeTrip(PaidApiService service, String breakerKey) {
        var threshold = properties.getInfosimplesFailureThreshold();
        if (threshold <= 0) return;
        var now = clock.instant();
        var windowStart = now.minusSeconds(properties.getBreakerWindowSeconds());
        var failures = recentFailures.computeIfAbsent(breakerKey, key -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(now);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(windowStart)) {
                failures.pollFirst();
            }
            if (failures.size() >= threshold) {
                var reopensAt = now.plusSeconds(properties.getBreakerCooldownSeconds());
                openUntil.put(breakerKey, reopensAt);
                failures.clear();
                log.warn("paid_api.circuit_tripped service={} breaker={} failures={} cooldownS={} reopensAt={}",
                        service, breakerKey, threshold, properties.getBreakerCooldownSeconds(), reopensAt);
            }
        }
    }

    private void persist(UUID userId, PaidApiService service, String uf, String provider,
                         boolean success, Integer resultCode) {
        try {
            repository.save(PaidApiCall.builder()
                    .userId(userId)
                    .service(service)
                    .uf(uf)
                    .provider(provider)
                    .success(success)
                    .resultCode(resultCode)
                    .estimatedCostCents(service.defaultCostCents())
                    .build());
        } catch (RuntimeException ex) {
            // The ledger must never break an otherwise-good ingest — a lost audit
            // row is recoverable from the provider invoice; a failed receipt is not.
            log.warn("paid_api.ledger_write_failed service={} reason={}", service, ex.getClass().getSimpleName());
        }
    }

    private int dailyCap(PaidApiService service) {
        return switch (service) {
            case INFOSIMPLES -> properties.getInfosimplesDailyCapPerUser();
            case CAPTCHA_SOLVE -> properties.getCaptchaDailyCapPerUser();
            case TWILIO_MESSAGE -> properties.getTwilioDailyCapPerUser();
            case LLM_ENRICH -> properties.getLlmEnrichDailyCapPerUser();
            case LLM_VISION -> properties.getLlmVisionDailyCapPerUser();
            case LLM_HTML_RESCUE -> properties.getLlmHtmlRescueDailyCapPerUser();
        };
    }

    private OffsetDateTime startOfTodayUtc() {
        return OffsetDateTime.now(clock).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }
}
