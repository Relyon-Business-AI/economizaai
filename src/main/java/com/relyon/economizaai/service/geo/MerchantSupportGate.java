package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import com.relyon.economizaai.repository.MarketLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Single decision point for whether a merchant's receipts are accepted and
 * whether they feed the collaborative price index. The rule is recurring
 * food/essentials RETAIL vs one-off food SERVICE:
 *
 * <ul>
 *   <li>{@code SUPPORTED} — retail segments (supermarket, pharmacy, food
 *       retail) or an admin SUPPORTED override: full ingest + price index.</li>
 *   <li>{@code BLOCKED} — food service (restaurantes/bares) or an admin
 *       BLOCKED override: the scan is rejected with a localized message and
 *       nothing beyond a failed tombstone is stored.</li>
 *   <li>{@code GREY} — everything else (OTHER, or UNKNOWN while the CNAE
 *       lookup hasn't succeeded): ingested normally so the user gets their
 *       result, but held OUT of the collaborative index until an admin
 *       reviews the sighting and promotes or blocks the merchant.</li>
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
        if (market.getSegment() == MerchantSegment.FOOD_SERVICE) return SupportStatus.BLOCKED;
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
