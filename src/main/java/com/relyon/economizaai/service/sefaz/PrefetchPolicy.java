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
 * Which UFs may submit receipts via {@code POST /receipts/prefetched}.
 * Pre-fetched content is client-authored — nothing but the embedded chave ties
 * it to SEFAZ — so the endpoint is restricted to UFs whose portal blocks our
 * datacenter IP and genuinely cannot be fetched server-side. Everywhere else
 * the normal scan flow's server fetch is the integrity check, and an attacker
 * must not be able to opt out of it.
 */
@Slf4j
@Component
public class PrefetchPolicy {

    private final Set<UnidadeFederativa> allowedUfs;

    public PrefetchPolicy(@Value("${economizaai.sefaz.prefetch-allowed-ufs:PE}") String allowedUfsCsv) {
        var parsed = EnumSet.noneOf(UnidadeFederativa.class);
        Arrays.stream(allowedUfsCsv == null ? new String[0] : allowedUfsCsv.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(UnidadeFederativa::valueOf)
                .forEach(parsed::add);
        this.allowedUfs = Set.copyOf(parsed);
        log.info("prefetch.policy allowed_ufs={}", this.allowedUfs);
    }

    public void requireAllowed(UnidadeFederativa uf) {
        if (!allowedUfs.contains(uf)) {
            log.warn("prefetch.rejected uf={} reason=server_fetchable", uf);
            throw new PrefetchedUnsupportedException(uf.name());
        }
    }
}
