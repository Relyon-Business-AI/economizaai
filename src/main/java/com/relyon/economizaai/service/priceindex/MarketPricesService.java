package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.MarketProductPriceResponse;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.PriceObservationRepository.MarketProductLatestPrice;
import com.relyon.economizaai.service.ProductService;
import com.relyon.economizaai.service.geo.CnpjNormalizer;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * "Tela do mercado": the latest community price of each product observed at ONE store.
 *
 * <p>Same k-anonymity-by-tier rule as best-markets ({@link PrivacyTierResolver}), applied per
 * product inside the query: a grocery product at a grocery-retail store may be disclosed from a
 * single household; a health product, or ANY product at a store not classified as grocery retail
 * (pharmacy / unknown segment — fail-closed), needs the full K.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPricesService {

    public static final int DEFAULT_DAYS = 30;
    /** Same ceiling as the community price window ({@code lookback-days}): older prices are shown nowhere. */
    public static final int MAX_DAYS = 90;
    public static final int DEFAULT_PAGE_SIZE = 30;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String ANY_NAME = "%";
    /** Placeholder bound when no category filter is active — never compared (see the query). */
    private static final ProductCategory UNUSED_CATEGORY = ProductCategory.OTHER;
    /** Keeps the {@code NOT IN} list non-empty when no sensitive category node is known. */
    private static final UUID NO_CATEGORY_ID = new UUID(0L, 0L);

    private final MarketLocationRepository marketLocationRepository;
    private final PriceObservationRepository observationRepository;
    private final PrivacyTierResolver privacyTierResolver;
    private final CollaborativeProperties properties;

    /** What the caller asked for; {@code days}/{@code page}/{@code size} are clamped here, not trusted. */
    public record Filter(String query, ProductCategory category, Integer days, MarketPricesSort sort,
                         int page, int size) {
    }

    @Transactional(readOnly = true)
    public Page<MarketProductPriceResponse> latestPrices(String cnpj, Filter filter) {
        var marketCnpj = CnpjNormalizer.normalize(cnpj);
        var market = marketLocationRepository.findByCnpj(marketCnpj)
                .orElseThrow(() -> new MarketNotFoundException(marketCnpj));
        var pageable = pageableFor(filter);
        if (!properties.getCollaborative().isEnabled()) {
            log.info("market_prices.skipped reason=master_switch_off cnpj={}", marketCnpj);
            return Page.empty(pageable);
        }

        var sensitiveMarket = PrivacyTierResolver.isSensitiveMarket(market);
        var storeTier = privacyTierResolver.thresholdsFor(sensitiveMarket);
        var sensitiveTier = privacyTierResolver.thresholdsFor(true);
        var days = clampDays(filter.days());
        var prices = observationRepository.findLatestDisclosablePricesAtMarket(
                marketCnpj, windowStart(days), namePattern(filter.query()),
                filter.category() == null, filter.category() == null ? UNUSED_CATEGORY : filter.category(),
                storeTier.minHouseholds(), storeTier.minObservations(),
                sensitiveTier.minHouseholds(), sensitiveTier.minObservations(),
                sensitiveCategoryIds(), pageable);

        log.info("market_prices.listed cnpj={} sensitiveMarket={} days={} sort={} category={} filtered={} page={} returned={} total={}",
                marketCnpj, sensitiveMarket, days, sortOrDefault(filter.sort()), filter.category(),
                hasText(filter.query()), pageable.getPageNumber(), prices.getNumberOfElements(),
                prices.getTotalElements());
        return prices.map(MarketPricesService::toResponse);
    }

    private static MarketProductPriceResponse toResponse(MarketProductLatestPrice price) {
        return new MarketProductPriceResponse(price.getProductId(), price.getProductName(), price.getCategory(),
                price.getLatestPrice(), price.getObservedAt().toLocalDate());
    }

    /** {@code observedAt} has day precision, so the window is counted in whole Brasília days. */
    private static LocalDateTime windowStart(int days) {
        return BrazilClock.today().minusDays(days).atStartOfDay();
    }

    static int clampDays(Integer days) {
        if (days == null) return DEFAULT_DAYS;
        return Math.clamp(days, 1, MAX_DAYS);
    }

    private static Pageable pageableFor(Filter filter) {
        var pageNumber = Math.max(0, filter.page());
        var pageSize = Math.clamp(filter.size(), 1, MAX_PAGE_SIZE);
        return PageRequest.of(pageNumber, pageSize, sortFor(sortOrDefault(filter.sort())));
    }

    private static MarketPricesSort sortOrDefault(MarketPricesSort sort) {
        return sort == null ? MarketPricesSort.RECENT : sort;
    }

    /** Name + id close every ordering, so pages never overlap when the leading key ties. */
    private static Sort sortFor(MarketPricesSort sort) {
        var byName = Sort.by(Sort.Order.asc("product.normalizedName"), Sort.Order.asc("product.id"));
        return switch (sort) {
            case RECENT -> Sort.by(Sort.Order.desc("po.observedAt")).and(byName);
            case CHEAPEST -> Sort.by(Sort.Order.asc("po.unitPrice")).and(byName);
            case NAME -> byName;
        };
    }

    private static String namePattern(String query) {
        return hasText(query) ? ProductService.containsPattern(query.trim()) : ANY_NAME;
    }

    private static boolean hasText(String query) {
        return query != null && !query.isBlank();
    }

    private Set<UUID> sensitiveCategoryIds() {
        var categoryIds = privacyTierResolver.sensitiveCategoryIds();
        return categoryIds.isEmpty() ? Set.of(NO_CATEGORY_ID) : categoryIds;
    }
}
