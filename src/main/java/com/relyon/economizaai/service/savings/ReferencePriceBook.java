package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.service.priceindex.PrivacyTierResolver;
import com.relyon.economizaai.service.priceindex.RobustStats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers "what would this line have cost at the usual price?". The reference is, in order:
 *
 * <ol>
 *   <li>REGION — the median of what OTHER households paid for the same product near the buyer,
 *       around the purchase day. Needs enough prices to be a typical price and must pass the
 *       k-anonymity tier of the product and of the stores the prices came from;</li>
 *   <li>HOUSEHOLD — the median of what the household itself paid for the product before;</li>
 *   <li>none — the line stays out of the savings instead of getting an invented number.</li>
 * </ol>
 */
final class ReferencePriceBook {

    enum Source { REGION, HOUSEHOLD }

    record Reference(BigDecimal unitPrice, Source source) {}

    /** A price another household paid, already narrowed to the buyer's region. */
    record RegionPrice(BigDecimal unitPrice, LocalDate day, UUID householdId, boolean sensitiveStore) {}

    record Settings(int regionWindowDays, int minRegionSamples, int ownLookbackDays, int minOwnSamples) {}

    private final Map<UUID, List<RegionPrice>> regionPricesByProduct;
    private final Map<UUID, List<PurchasedLine>> ownLinesByProduct;
    private final PrivacyTierResolver privacyTierResolver;
    private final Settings settings;

    ReferencePriceBook(Map<UUID, List<RegionPrice>> regionPricesByProduct,
                       Map<UUID, List<PurchasedLine>> ownLinesByProduct,
                       PrivacyTierResolver privacyTierResolver, Settings settings) {
        this.regionPricesByProduct = regionPricesByProduct;
        this.ownLinesByProduct = ownLinesByProduct;
        this.privacyTierResolver = privacyTierResolver;
        this.settings = settings;
    }

    Optional<Reference> referenceFor(PurchasedLine line) {
        return regionReference(line).or(() -> householdReference(line));
    }

    private Optional<Reference> regionReference(PurchasedLine line) {
        var pricesAroundPurchase = regionPricesByProduct.getOrDefault(line.product().getId(), List.of()).stream()
                .filter(regionPrice -> isAroundPurchaseDay(regionPrice.day(), line.day()))
                .toList();
        if (pricesAroundPurchase.size() < settings.minRegionSamples() || isBlockedByPrivacyTier(line, pricesAroundPurchase)) {
            return Optional.empty();
        }
        var unitPrices = pricesAroundPurchase.stream().map(RegionPrice::unitPrice).toList();
        return Optional.of(new Reference(typicalPrice(unitPrices), Source.REGION));
    }

    private boolean isAroundPurchaseDay(LocalDate priceDay, LocalDate purchaseDay) {
        return !priceDay.isBefore(purchaseDay.minusDays(settings.regionWindowDays()))
                && !priceDay.isAfter(purchaseDay.plusDays(settings.regionWindowDays()));
    }

    /** Health products and prices from a pharmacy or unclassified store keep the full K. */
    private boolean isBlockedByPrivacyTier(PurchasedLine line, List<RegionPrice> regionPrices) {
        var sensitive = privacyTierResolver.isSensitiveProduct(line.product())
                || regionPrices.stream().anyMatch(RegionPrice::sensitiveStore);
        var contributingHouseholds = regionPrices.stream().map(RegionPrice::householdId).distinct().count();
        return privacyTierResolver.thresholdsFor(sensitive).blocks(regionPrices.size(), contributingHouseholds);
    }

    private Optional<Reference> householdReference(PurchasedLine line) {
        var earliestDay = line.day().minusDays(settings.ownLookbackDays());
        var earlierUnitPrices = ownLinesByProduct.getOrDefault(line.product().getId(), List.of()).stream()
                .filter(ownLine -> ownLine.day().isBefore(line.day()) && !ownLine.day().isBefore(earliestDay))
                .map(PurchasedLine::paidUnitPrice)
                .toList();
        if (earlierUnitPrices.size() < settings.minOwnSamples()) return Optional.empty();
        return Optional.of(new Reference(typicalPrice(earlierUnitPrices), Source.HOUSEHOLD));
    }

    private static BigDecimal typicalPrice(List<BigDecimal> unitPrices) {
        return PriceIndexService.median(RobustStats.withoutOutliers(unitPrices));
    }
}
