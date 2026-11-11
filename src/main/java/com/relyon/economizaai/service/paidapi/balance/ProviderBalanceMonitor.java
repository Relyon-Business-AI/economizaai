package com.relyon.economizaai.service.paidapi.balance;

import com.relyon.economizaai.model.ProviderBalanceState;
import com.relyon.economizaai.repository.ProviderBalanceStateRepository;
import com.relyon.economizaai.service.paidapi.CostAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hourly watch over the PREPAID balance of each paid provider, with the same 80% / 100%
 * rule as the daily budgets:
 * <ul>
 *   <li>the reference is the balance right after the last detected top-up (the balance
 *       went UP), or the configured credit for a provider with no balance API;</li>
 *   <li>80% of that reference consumed → warning; balance exhausted (at or below about
 *       one unit of the service's cost) → the 100% alert.</li>
 * </ul>
 * Each alert goes out once per top-up cycle; dedup and guaranteed delivery live in
 * {@link CostAlertService}. Every provider is isolated (one failing never stops the
 * others), unconfigured providers are skipped, and the balance read (HTTP) happens with
 * no transaction open — the state is saved with a plain repository call afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderBalanceMonitor {

    private final List<ProviderBalanceSource> sources;
    private final ProviderBalanceStateRepository stateRepository;
    private final CostAlertService costAlertService;
    private Clock clock = Clock.systemUTC();

    /** Test seam — pin "now". */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(cron = "${economizaai.cost-alerts.balance-check-cron:0 5 * * * *}", zone = "America/Sao_Paulo")
    public void checkBalances() {
        if (!costAlertService.isEnabled()) return;
        var anyRaised = false;
        for (var source : sources) {
            try {
                anyRaised |= checkProvider(source);
            } catch (RuntimeException providerFailure) {
                log.warn("provider_balance.check_failed provider={} reason={}",
                        source.providerKey(), providerFailure.getClass().getSimpleName());
            }
        }
        if (anyRaised) {
            costAlertService.dispatchPending();
        }
    }

    private boolean checkProvider(ProviderBalanceSource source) {
        if (!source.isConfigured()) {
            log.debug("provider_balance.skipped provider={} reason=not_configured", source.providerKey());
            return false;
        }
        var balance = source.fetchBalance().orElse(null);
        if (balance == null) {
            log.warn("provider_balance.unreadable provider={}", source.providerKey());
            return false;
        }
        var state = trackReference(source, balance);
        log.info("provider_balance.checked provider={} balance={} reference={}",
                source.providerKey(), balance, state.getReferenceBalance());
        return raiseIfThresholdReached(source, state, balance);
    }

    /** Updates the stored reading; a top-up (or a changed configured credit) starts a new reference cycle. */
    private ProviderBalanceState trackReference(ProviderBalanceSource source, BigDecimal balance) {
        var now = OffsetDateTime.now(clock);
        var state = stateRepository.findById(source.providerKey())
                .orElseGet(() -> ProviderBalanceState.builder()
                        .provider(source.providerKey())
                        .referenceBalance(balance)
                        .referenceAt(now)
                        .lastBalance(balance)
                        .build());
        var configuredCredit = source.configuredCredit().orElse(null);
        if (configuredCredit != null) {
            if (state.getReferenceBalance().compareTo(configuredCredit) != 0) {
                startNewCycle(state, configuredCredit, now);
            }
        } else if (balance.subtract(state.getLastBalance()).compareTo(source.minimumTopUp()) >= 0) {
            log.info("provider_balance.topup_detected provider={} from={} to={}",
                    source.providerKey(), state.getLastBalance(), balance);
            startNewCycle(state, balance, now);
        }
        state.setLastBalance(balance);
        state.setLastCheckedAt(now);
        return stateRepository.save(state);
    }

    private static void startNewCycle(ProviderBalanceState state, BigDecimal reference, OffsetDateTime now) {
        state.setReferenceBalance(reference);
        state.setReferenceAt(now);
    }

    private boolean raiseIfThresholdReached(ProviderBalanceSource source, ProviderBalanceState state,
                                            BigDecimal balance) {
        var reference = state.getReferenceBalance();
        var exhausted = balance.compareTo(source.exhaustedAtOrBelow()) <= 0;
        var threshold = exhausted
                ? CostAlertService.LIMIT_THRESHOLD
                : Math.min(CostAlertService.WARNING_THRESHOLD,
                        CostAlertService.thresholdReached(reference.subtract(balance), reference));
        if (threshold == 0) return false;
        var symbol = source.currencySymbol();
        var headline = exhausted
                ? "Economiza AI: saldo da %s ESGOTADO (%s). Parado até recarregar: %s."
                        .formatted(source.displayName(), CostAlertService.money(symbol, balance), source.whatStops())
                : "Economiza AI: saldo da %s com 80%% consumido (restam %s de %s). Ao zerar: %s."
                        .formatted(source.displayName(), CostAlertService.money(symbol, balance),
                                CostAlertService.money(symbol, reference), source.whatStops());
        var detail = headline + "\n\nReferência: saldo após a última recarga detectada ("
                + CostAlertService.money(symbol, reference) + " em " + state.getReferenceAt() + ").";
        var cycleKey = String.valueOf(state.getReferenceAt().toEpochSecond());
        return costAlertService.raise("BALANCE:" + source.providerKey(), threshold, cycleKey, headline, detail);
    }
}
