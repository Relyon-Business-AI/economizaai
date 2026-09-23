package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import com.relyon.economizaai.repository.MarketLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Single decision point for whether a merchant's receipts feed the collaborative
 * price index and our algorithms. We accept ANY nota into the user's personal
 * history, but only grocery/pharmacy — our specialty — power the shared index,
 * metrics and training:
 *
 * <ul>
 *   <li>{@code SUPPORTED} — grocery/pharmacy segments (supermarket, pharmacy,
 *       food retail) or an admin SUPPORTED override: ingest + feed the index.</li>
 *   <li>{@code BLOCKED} — ONLY an explicit admin BLOCKED override (spam/fraud
 *       safety valve): rejected, nothing beyond a failed tombstone is stored.</li>
 *   <li>{@code GREY} — everything else (restaurants, pet, apparel, e-commerce,
 *       OTHER, or UNKNOWN while the CNAE lookup hasn't succeeded): ingested
 *       normally into personal history, but held OUT of the shared index.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MerchantSupportGate {

    private static final Set<MerchantSegment> SUPPORTED_SEGMENTS =
            Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.PHARMACY, MerchantSegment.FOOD_RETAIL);

    private final MarketLocationRepository marketLocationRepository;
    private final CnpjActivityClient cnpjActivityClient;

    public enum SupportStatus { SUPPORTED, GREY, BLOCKED }

    public SupportStatus statusOf(MarketLocation market) {
        if (market == null) return SupportStatus.GREY;
        if (market.getSupportOverride() == MerchantSupportOverride.SUPPORTED) return SupportStatus.SUPPORTED;
        if (market.getSupportOverride() == MerchantSupportOverride.BLOCKED) return SupportStatus.BLOCKED;
        if (SUPPORTED_SEGMENTS.contains(market.getSegment())) return SupportStatus.SUPPORTED;
        // Everything else (restaurants, pet, apparel, e-commerce, not-yet-classified) is GREY:
        // ingested into the user's personal history but held OUT of the shared index — only
        // grocery/pharmacy feed the index and our algorithms. Nothing is auto-blocked; only an
        // explicit admin BLOCKED override rejects a merchant.
        return SupportStatus.GREY;
    }

    /** Blocked = reject the scan. Known-blocked CNPJs fail at submit; the rest during ingest. */
    public boolean isBlocked(MarketLocation market) {
        return statusOf(market) == SupportStatus.BLOCKED;
    }

    /** Submit-time check: only a previously-seen (and classified) CNPJ can reject synchronously. */
    public boolean isKnownBlockedCnpj(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) return false;
        return marketLocationRepository.findByCnpj(cnpj).map(this::isBlocked).orElse(false);
    }

    /**
     * Whether this merchant's confirmed receipts contribute PriceObservations.
     * Grey merchants don't — their prices would pollute the shared index until
     * an admin reviews them. When CNAE classification is disabled entirely
     * (dev environments), fail open so the index isn't silently starved.
     */
    public boolean contributesToIndex(MarketLocation market) {
        var status = statusOf(market);
        if (status == SupportStatus.SUPPORTED) return true;
        if (status == SupportStatus.BLOCKED) return false;
        // GREY waits for admin review — except when classification is off entirely
        // (dev environments): fail open so the index isn't silently starved.
        return !cnpjActivityClient.isEnabled()
                && (market == null || market.getSegment() == MerchantSegment.UNKNOWN);
    }
}
