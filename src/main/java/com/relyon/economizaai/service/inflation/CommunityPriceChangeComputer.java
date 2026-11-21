package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.model.CommunityPriceChange;
import com.relyon.economizaai.model.CommunityPriceCoverage;
import com.relyon.economizaai.repository.CommunityPriceChangeRepository;
import com.relyon.economizaai.repository.CommunityPriceChangeRepository.IndexObservation;
import com.relyon.economizaai.repository.CommunityPriceCoverageRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.MonthChanges;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.PriceSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Computes, month by month, how the community's food prices moved from the previous month, and
 * replaces the stored rows of that month — running it again for the same month gives the same
 * rows. Only two months of observations are in memory at a time.
 */
@Slf4j
@Service
public class CommunityPriceChangeComputer {

    public record RecomputeResult(int months, int productRows, int pairs) {}

    private final CommunityPriceChangeRepository changeRepository;
    private final CommunityPriceCoverageRepository coverageRepository;
    private final CategoryResolver categoryResolver;
    private final PriceInflationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public CommunityPriceChangeComputer(CommunityPriceChangeRepository changeRepository,
                                        CommunityPriceCoverageRepository coverageRepository,
                                        CategoryResolver categoryResolver, PriceInflationProperties properties,
                                        PlatformTransactionManager transactionManager) {
        this.changeRepository = changeRepository;
        this.coverageRepository = coverageRepository;
        this.categoryResolver = categoryResolver;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public boolean hasNoMonth() {
        return coverageRepository.count() == 0;
    }

    /** Recomputes the {@code months} months that end at {@code lastMonth}, oldest first. */
    public RecomputeResult recomputeMonthsEndingAt(YearMonth lastMonth, int months) {
        var productRows = 0;
        var pairs = 0;
        for (var month = lastMonth.minusMonths(months - 1L); !month.isAfter(lastMonth); month = month.plusMonths(1)) {
            var changes = recomputeMonth(month);
            productRows += changes.productChanges().size();
            pairs += changes.coverage().stream().mapToInt(CommunityIndexMath.StateCoverage::pairCount).sum();
        }
        log.info("price_inflation.community.recomputed months={} last={} product_rows={} pairs={}",
                months, lastMonth, productRows, pairs);
        return new RecomputeResult(months, productRows, pairs);
    }

    public MonthChanges recomputeMonth(YearMonth month) {
        var changes = monthChanges(month);
        transactionTemplate.executeWithoutResult(status -> replaceMonth(month, changes));
        return changes;
    }

    private MonthChanges monthChanges(YearMonth month) {
        var foodCategoryIds = foodCategoryIds();
        if (foodCategoryIds.isEmpty()) return new MonthChanges(List.of(), List.of());
        var previousMonth = month.minusMonths(1);
        var monthStart = month.atDay(1).atStartOfDay();
        var observations = changeRepository.findIndexObservations(foodCategoryIds,
                previousMonth.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
        var previousSamples = new ArrayList<PriceSample>();
        var currentSamples = new ArrayList<PriceSample>();
        for (var observation : observations) {
            var samples = observation.getObservedAt().isBefore(monthStart) ? previousSamples : currentSamples;
            samples.add(sampleOf(observation));
        }
        return CommunityIndexMath.monthChanges(previousSamples, currentSamples, properties.getMaxMonthlyChangePercent());
    }

    /** The configured root categories plus their subcategories. */
    private Set<UUID> foodCategoryIds() {
        var categoryIds = new HashSet<UUID>();
        properties.getFoodCategories().forEach(slug -> categoryIds.addAll(categoryResolver.nodeAndDescendantIds(slug)));
        return categoryIds;
    }

    /** Prices are compared per kg / litre / unit when the observation has that form; otherwise as printed. */
    private static PriceSample sampleOf(IndexObservation observation) {
        var normalized = observation.getNormalizedUnitPrice() != null && observation.getNormalizedUnit() != null;
        var basis = normalized ? observation.getNormalizedUnit() : CommunityIndexMath.RAW_BASIS;
        var price = normalized ? observation.getNormalizedUnitPrice() : observation.getUnitPrice();
        var spend = observation.getUnitPrice().multiply(observation.getQuantity());
        return new PriceSample(observation.getProductId(), observation.getMarketCnpj(), observation.getState(), basis,
                price.doubleValue(), spend.doubleValue());
    }

    private void replaceMonth(YearMonth month, MonthChanges changes) {
        var referenceMonth = month.atDay(1);
        changeRepository.deleteMonth(referenceMonth);
        coverageRepository.deleteMonth(referenceMonth);
        changeRepository.saveAll(changes.productChanges().stream()
                .map(change -> CommunityPriceChange.builder()
                        .referenceMonth(referenceMonth)
                        .state(change.state())
                        .productId(change.productId())
                        .weightedLogSum(change.weightedLogSum())
                        .weightSum(change.weightSum())
                        .pairCount(change.pairCount())
                        .build())
                .toList());
        coverageRepository.saveAll(changes.coverage().stream()
                .map(coverage -> CommunityPriceCoverage.builder()
                        .referenceMonth(referenceMonth)
                        .state(coverage.state())
                        .pairCount(coverage.pairCount())
                        .storeCount(coverage.storeCount())
                        .build())
                .toList());
    }
}
