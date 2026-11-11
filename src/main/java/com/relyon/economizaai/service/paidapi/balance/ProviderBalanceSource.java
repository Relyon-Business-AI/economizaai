package com.relyon.economizaai.service.paidapi.balance;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * One paid provider whose PREPAID balance is watched by {@link ProviderBalanceMonitor}.
 * Implementations do the (untransacted, timeout-bounded) HTTP read; the monitor owns the
 * top-up tracking and the 80% / exhausted alerts.
 */
public interface ProviderBalanceSource {

    /** Stable key for state and dedup, e.g. {@code CAPSOLVER}. */
    String providerKey();

    /** Name used in the alert wording, e.g. "CapSolver". */
    String displayName();

    /** "R$" or "US$" — the currency the balance is held in. */
    String currencySymbol();

    /** False when the provider has no credentials here — it is skipped silently. */
    boolean isConfigured();

    /** Current balance, or empty when it could not be read right now. Must not throw. */
    Optional<BigDecimal> fetchBalance();

    /** At or below this (about one unit of the service's cost) the balance counts as exhausted. */
    BigDecimal exhaustedAtOrBelow();

    /** A balance increase of at least this is a top-up (smaller bumps are refunds/noise). */
    BigDecimal minimumTopUp();

    /** What stops working when the balance runs out — completes "Ao zerar, ...". */
    String whatStops();

    /**
     * For providers with no balance API: the configured prepaid credit, used as the fixed
     * reference instead of a detected top-up. Empty for providers that report a balance.
     */
    default Optional<BigDecimal> configuredCredit() {
        return Optional.empty();
    }
}
