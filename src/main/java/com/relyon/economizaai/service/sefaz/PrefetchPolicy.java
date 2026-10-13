package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.PrefetchedUnsupportedException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Governs on-device fetch ({@code POST /receipts/prefetched}) along two axes:
 *
 * <ul>
 *   <li><b>Eligibility</b> — may this UF be resolved on-device at all? Pre-fetched
 *       content is client-authored (only the embedded chave ties it to SEFAZ), so
 *       a receipt opting out of our server-side fetch is an integrity concern.</li>
 *   <li><b>Trust</b> — does this UF's device-fetched content feed the collaborative
 *       index, or only the user's personal history? The trust allowlist
 *       ({@code prefetch-allowed-ufs}, e.g. PE) names the UFs whose portal blocks our
 *       datacenter IP by design and whose on-device path we've validated.</li>
 * </ul>
 *
 * <p>Default (generic fallback OFF): eligibility == trust == the allowlist — the
 * long-standing behavior, PE only, device-fetch content index-eligible.
 *
 * <p>Generic fallback ON ({@code device-fetch.generic-enabled}): ANY UF becomes
 * eligible (a server-fetch failure can defer to the user's residential IP), but
 * only trusted UFs' device-fetch content is index-eligible — untrusted content is
 * personal-history only (marked {@code index_eligible=false}), the same trust class
 * as a PHOTO receipt. This widens the device-fetch lifeline without widening the
 * forgeable-content surface of the shared index.
 */
@Slf4j
@Component
public class PrefetchPolicy {

    /** UFs whose on-device fetch is TRUSTED for the collaborative index (validated, IP-blocked by design). */
    private final Set<UnidadeFederativa> trustedUfs;
    /** When true, any UF may device-fetch (eligibility generalized); trust still gates the index. */
    private final boolean genericEnabled;

    public PrefetchPolicy(@Value("${economizaai.sefaz.prefetch-allowed-ufs:PE}") String allowedUfsCsv,
                          @Value("${economizaai.ingestion.device-fetch.generic-enabled:false}") boolean genericEnabled) {
        var parsed = EnumSet.noneOf(UnidadeFederativa.class);
        Arrays.stream(allowedUfsCsv == null ? new String[0] : allowedUfsCsv.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(UnidadeFederativa::valueOf)
                .forEach(parsed::add);
        this.trustedUfs = Set.copyOf(parsed);
        this.genericEnabled = genericEnabled;
        log.info("prefetch.policy trusted_ufs={} generic_enabled={}", this.trustedUfs, this.genericEnabled);
    }

    /**
     * Gate for {@code POST /receipts/prefetched}: rejects a UF that may not device-fetch.
     * With the generic fallback off, only trusted UFs qualify; with it on, any UF does
     * (the integrity trade-off is handled downstream by index-exclusion, not by refusing
     * the receipt into the user's own history).
     */
    public void requireAllowed(UnidadeFederativa uf) {
        if (!isDeviceFetchEligible(uf)) {
            log.warn("prefetch.rejected uf={} reason=server_fetchable", uf);
            throw new PrefetchedUnsupportedException(uf.name());
        }
    }

    /**
     * True when the UF may be resolved on-device. Drives the server-fetch-failure
     * hand-off to {@code NEEDS_DEVICE_FETCH} and the experimental evidence-skip
     * (a UF we'll device-fetch must not accrue server-side EXHAUSTED evidence).
     */
    public boolean isDeviceFetchEligible(UnidadeFederativa uf) {
        return genericEnabled || trustedUfs.contains(uf);
    }

    /** True when the UF's device-fetched content is TRUSTED for the collaborative index. */
    public boolean isTrustedForIndex(UnidadeFederativa uf) {
        return trustedUfs.contains(uf);
    }

    /** Back-compat alias — same meaning as {@link #isDeviceFetchEligible}. */
    public boolean isDeviceFetchUf(UnidadeFederativa uf) {
        return isDeviceFetchEligible(uf);
    }

    public boolean isGenericEnabled() {
        return genericEnabled;
    }
}
