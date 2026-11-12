package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.HouseholdMarketAlias;
import com.relyon.economizaai.model.MarketChain;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizaai.repository.MarketChainRepository;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MarketLocationRepository.MarketTradeName;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves the market display name for a household. Every display name follows the
 * same cascade: the household's custom rename ({@link HouseholdMarketAlias}, keyed by
 * (householdId, CNPJ) and never visible to other households) &rarr; the market's
 * nome fantasia from the public CNPJ registry &rarr; the razão social with the
 * corporate boilerplate stripped and title-cased. The raw legal name is always kept
 * separately so the FE can still show it in detail.
 *
 * <p>Use {@link #resolveNames} to batch-load the names for a whole response and
 * avoid N+1 lookups; use {@link #resolve} for single values.
 *
 * <p>{@link #friendlyName} additionally tries the global {@link MarketChain} name
 * (keyed by CNPJ root) before falling back to the cleaned razão social.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketNameService {

    private final HouseholdMarketAliasRepository aliasRepository;
    private final MarketChainRepository marketChainRepository;
    private final MarketLocationRepository marketLocationRepository;
    private final HouseholdCacheGen householdCacheGen;

    /** Corporate boilerplate stripped from a razão social before title-casing. Longest first. */
    private static final Pattern CORPORATE_BOILERPLATE = Pattern.compile(
            "(?i)\\b(" +
                    "distribuidora de alimentos|comercio e industria|com\\. e ind\\.|com e ind|" +
                    "comercio de alimentos|comercio de generos alimenticios|supermercados|supermercado|" +
                    "comercial|comercio|industria|sociedade anonima|" +
                    "s\\.?/?\\.?a\\.?|s\\s+a|ltda\\.?|eireli|epp|me|mei" +
                    ")\\b\\.?");

    /** Punctuation left dangling once boilerplate is removed (e.g. trailing " - ", " , "). */
    private static final Pattern DANGLING_PUNCTUATION = Pattern.compile("[\\s,\\-/]+$|^[\\s,\\-/]+");

    /** Small connective words that stay lowercase inside a title-cased name. */
    private static final Set<String> LOWERCASE_CONNECTIVES =
            Set.of("de", "da", "do", "das", "dos", "e");

    /**
     * Display name for one market: household rename, else nome fantasia, else {@code rawName} cleaned.
     * {@code rawName} is the razão social as printed on the receipt.
     */
    @Transactional(readOnly = true)
    public String resolve(UUID householdId, String cnpj, String rawName) {
        if (cnpj == null) return cleanLegalName(rawName);
        if (householdId != null) {
            var rename = aliasRepository.findByHouseholdIdAndMarketCnpj(householdId, cnpj)
                    .map(HouseholdMarketAlias::getCustomName);
            if (rename.isPresent()) return rename.get();
        }
        return applyOverride(tradeNamesFor(List.of(cnpj)), cnpj, rawName);
    }

    /**
     * Batch: cnpj -> name that replaces the razão social, for the markets that have one. A household
     * rename wins over the nome fantasia; a market with neither is absent from the map.
     */
    @Transactional(readOnly = true)
    public Map<String, String> resolveNames(UUID householdId, Collection<String> cnpjs) {
        if (cnpjs == null || cnpjs.isEmpty()) return Map.of();
        var names = new HashMap<>(tradeNamesFor(cnpjs));
        if (householdId != null) {
            aliasRepository.findAllByHouseholdIdAndMarketCnpjIn(householdId, cnpjs)
                    .forEach(alias -> names.put(alias.getMarketCnpj(), alias.getCustomName()));
        }
        return names;
    }

    /** Apply a name map (from {@link #resolveNames}) to one market, falling back to the cleaned razão social. */
    public String applyOverride(Map<String, String> overrides, String cnpj, String rawName) {
        if (overrides == null || cnpj == null) return cleanLegalName(rawName);
        var name = overrides.get(cnpj);
        return name != null && !name.isBlank() ? name : cleanLegalName(rawName);
    }

    /** cnpj -> nome fantasia ready for display, for the markets whose registry entry has a usable one. */
    private Map<String, String> tradeNamesFor(Collection<String> cnpjs) {
        return marketLocationRepository.findTradeNamesByCnpjIn(cnpjs).stream()
                .filter(market -> isUsableTradeName(market.getTradeName()))
                .collect(Collectors.toMap(MarketTradeName::getCnpj,
                        market -> formatTradeName(market.getTradeName()), (first, ignored) -> first));
    }

    /** The registry sometimes carries filler ("********", "0") in the nome fantasia field; require real letters. */
    public static boolean isUsableTradeName(String tradeName) {
        return tradeName != null && tradeName.chars().filter(Character::isLetter).count() >= 2;
    }

    /** The registry stores the nome fantasia in upper case; title-case it for display (nothing is stripped). */
    public static String formatTradeName(String tradeName) {
        return titleCase(tradeName.trim());
    }

    /** Batch: CNPJ root -&gt; global chain name, for the full CNPJs given (only known chains present). */
    @Transactional(readOnly = true)
    public Map<String, String> resolveChainNames(Collection<String> cnpjs) {
        if (cnpjs == null || cnpjs.isEmpty()) return Map.of();
        return cnpjs.stream()
                .filter(cnpj -> cnpj != null && cnpj.length() >= 8)
                .map(cnpj -> cnpj.substring(0, 8))
                .distinct()
                .map(marketChainRepository::findByCnpjRoot)
                .flatMap(Optional::stream)
                .filter(chain -> chain.getName() != null && !chain.getName().isBlank())
                .collect(Collectors.toMap(MarketChain::getCnpjRoot, MarketChain::getName, (first, ignored) -> first));
    }

    /**
     * Friendly display name using pre-loaded batch maps (no DB access — safe inside a stream).
     * Cascade: household rename &rarr; nome fantasia (both already in {@code overrides}) &rarr; global
     * chain name &rarr; cleaned razão social.
     */
    public String friendlyName(Map<String, String> overrides, Map<String, String> chainNames,
                               String cnpj, String rawName) {
        // A household rename is user-authored and the nome fantasia is already formatted — no cleaning.
        if (cnpj != null && overrides != null) {
            var rename = overrides.get(cnpj);
            if (rename != null && !rename.isBlank()) return rename;
        }
        // Chain name is the next-best BASE, but it's stored as the legal razão social, so it still
        // goes through the cleaning heuristic (same as the raw name) before display.
        if (cnpj != null && chainNames != null && cnpj.length() >= 8) {
            var chainName = chainNames.get(cnpj.substring(0, 8));
            if (chainName != null && !chainName.isBlank()) return cleanLegalName(chainName);
        }
        return cleanLegalName(rawName);
    }

    /** Single-value friendly name (loads the two maps itself); prefer the batch overload in loops. */
    @Transactional(readOnly = true)
    public String friendlyName(UUID householdId, String cnpj, String rawName) {
        var overrides = householdId == null || cnpj == null
                ? Map.<String, String>of()
                : resolveNames(householdId, List.of(cnpj));
        var chainNames = cnpj == null ? Map.<String, String>of() : resolveChainNames(List.of(cnpj));
        return friendlyName(overrides, chainNames, cnpj, rawName);
    }

    /**
     * Strip corporate boilerplate ("COMERCIO E INDUSTRIA", "LTDA", "S.A.", "EIRELI", trailing "ME",
     * etc.) from a razão social and title-case the remainder. Falls back to the original when the
     * cleaning would leave nothing useful (e.g. the name WAS the boilerplate).
     */
    public static String cleanLegalName(String rawName) {
        if (rawName == null || rawName.isBlank()) return rawName;
        var stripped = CORPORATE_BOILERPLATE.matcher(rawName).replaceAll(" ").replaceAll("\\s{2,}", " ");
        stripped = DANGLING_PUNCTUATION.matcher(stripped.trim()).replaceAll("").trim();
        if (stripped.isBlank()) return titleCase(rawName.trim());
        return titleCase(stripped);
    }

    private static String titleCase(String value) {
        return Arrays.stream(value.toLowerCase().split("\\s+"))
                .filter(word -> !word.isBlank())
                .map(MarketNameService::capitalizeWord)
                .collect(Collectors.joining(" "));
    }

    private static String capitalizeWord(String word) {
        if (LOWERCASE_CONNECTIVES.contains(word)) return word;
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
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
        // The friendly name is baked into cached dashboard/insights payloads — invalidate them.
        householdCacheGen.bump(householdId);
        log.info("market_alias.set user={} cnpj={} name='{}'", LogMasker.email(user.getEmail()), normalized, saved.getCustomName());
        return saved.getCustomName();
    }

    @Transactional
    public void clearName(User user, String cnpj) {
        var normalized = CnpjNormalizer.normalize(cnpj);
        var householdId = user.getHousehold().getId();
        aliasRepository.deleteByHouseholdIdAndMarketCnpj(householdId, normalized);
        householdCacheGen.bump(householdId);
        log.info("market_alias.cleared user={} cnpj={}", LogMasker.email(user.getEmail()), normalized);
    }
}
