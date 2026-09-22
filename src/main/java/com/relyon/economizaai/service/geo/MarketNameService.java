package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.HouseholdMarketAlias;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the market display name for a household, honoring a per-household
 * custom rename ({@link HouseholdMarketAlias}) over the global name. The
 * override is keyed by (householdId, CNPJ) and is never visible to other
 * households.
 *
 * <p>Use {@link #resolveNames} to batch-load overrides for a whole response and
 * avoid N+1 lookups; use {@link #resolve} for single values.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketNameService {

    private final HouseholdMarketAliasRepository aliasRepository;

    /** Custom name for one market, or {@code fallback} when the household hasn't renamed it. */
    @Transactional(readOnly = true)
    public String resolve(UUID householdId, String cnpj, String fallback) {
        if (householdId == null || cnpj == null) return fallback;
        return aliasRepository.findByHouseholdIdAndMarketCnpj(householdId, cnpj)
                .map(HouseholdMarketAlias::getCustomName)
                .orElse(fallback);
    }

    /** Batch: cnpj -> custom name for the household (only renamed markets present). */
    @Transactional(readOnly = true)
    public Map<String, String> resolveNames(UUID householdId, Collection<String> cnpjs) {
        if (householdId == null || cnpjs == null || cnpjs.isEmpty()) return Map.of();
        return aliasRepository.findAllByHouseholdIdAndMarketCnpjIn(householdId, cnpjs).stream()
                .collect(Collectors.toMap(HouseholdMarketAlias::getMarketCnpj, HouseholdMarketAlias::getCustomName));
    }

    /** Apply an override map (from {@link #resolveNames}) to one name, falling back when absent. */
    public String applyOverride(Map<String, String> overrides, String cnpj, String fallback) {
        if (overrides == null || cnpj == null) return fallback;
        return overrides.getOrDefault(cnpj, fallback);
    }

    @Transactional
    public String setName(User user, String cnpj, String customName) {
        var normalized = CnpjNormalizer.normalize(cnpj);
        var householdId = user.getHousehold().getId();
        var alias = aliasRepository.findByHouseholdIdAndMarketCnpj(householdId, normalized)
                .orElseGet(() -> HouseholdMarketAlias.builder()
                        .household(user.getHousehold()).marketCnpj(normalized).build());
        alias.setCustomName(customName.trim());
        var saved = aliasRepository.save(alias);
        log.info("market_alias.set user={} cnpj={} name='{}'", LogMasker.email(user.getEmail()), normalized, saved.getCustomName());
        return saved.getCustomName();
    }

    @Transactional
    public void clearName(User user, String cnpj) {
        var normalized = CnpjNormalizer.normalize(cnpj);
        aliasRepository.deleteByHouseholdIdAndMarketCnpj(user.getHousehold().getId(), normalized);
        log.info("market_alias.cleared user={} cnpj={}", LogMasker.email(user.getEmail()), normalized);
    }
}
