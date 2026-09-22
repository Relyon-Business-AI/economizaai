package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Two flavours of promo detection:
 *
 * <ol>
 *   <li><b>Personal</b> — runs on every confirm. Per item, compares the
 *       paid unit price against the user's own historical median for the
 *       same product. Works with one user, one household — needs only
 *       {@link CollaborativeProperties.PersonalPromo#getMinPurchasesForBaseline()}
 *       prior purchases.</li>
 *   <li><b>Community</b> — runs as a periodic scan against the
 *       PriceObservation table. Compares each (product, market) median
 *       over a recent window vs the long-term median. K-anon protected.
 *       Requires real volume to fire.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromoDetector {

    private final ReceiptItemRepository receiptItemRepository;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public List<PersonalPromo> detectPersonalPromos(Receipt receipt) {
        var promos = new ArrayList<PersonalPromo>();
        var householdId = receipt.getHousehold().getId();
        for (var item : receipt.getItems()) {
            // Personal notification — skip "not mine" lines too (no promo alert for someone else's item).
            if (item.isExcluded() || item.isExcludedFromPersonal()) continue;
            if (item.getProduct() == null || item.getUnitPrice() == null) continue;
            var historical = receiptItemRepository
                    .findHouseholdHistoryForProduct(item.getProduct().getId(), householdId).stream()
                    .filter(prev -> !prev.getId().equals(item.getId()))
                    .filter(prev -> !prev.isExcluded())
                    .filter(prev -> prev.getUnitPrice() != null)
                    .toList();
            if (historical.size() < properties.getPersonalPromo().getMinPurchasesForBaseline()) continue;

            var median = PriceIndexService.median(historical.stream()
                    .map(previous -> previous.getUnitPrice())
                    .toList());
            var threshold = median
                    .multiply(BigDecimal.valueOf(100L - properties.getPersonalPromo().getThresholdPct()))
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

            if (item.getUnitPrice().compareTo(threshold) < 0) {
                var savingsPct = median.subtract(item.getUnitPrice())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(median, 2, RoundingMode.HALF_UP);
                promos.add(new PersonalPromo(
                        item.getId(),
                        item.getProduct().getId(),
                        item.getProduct().getNormalizedName(),
                        item.getUnitPrice(),
                        median,
                        savingsPct,
                        historical.size()
                ));
                log.info("personal_promo.detected receipt={} item={} product='{}' paid={} median={} savings={}%",
                        receipt.getId(), item.getId(), item.getProduct().getNormalizedName(),
                        item.getUnitPrice(), median, savingsPct);
            }
        }
        return promos;
    }

    public record PersonalPromo(
            UUID receiptItemId,
            UUID productId,
            String productName,
            BigDecimal paidPrice,
            BigDecimal historicalMedian,
            BigDecimal savingsPct,
            int baselineSize
    ) {}
}
