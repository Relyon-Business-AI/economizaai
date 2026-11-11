package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // Nota de FOTO tem preço de OCR (não verificado) — nunca dispara "você economizou":
        // comparar um preço bagunçado contra o histórico gera alerta falso.
        if (receipt.getOrigin() == ReceiptOrigin.PHOTO) {
            return promos;
        }
        var candidates = receipt.getItems().stream().filter(PromoDetector::isPromoCandidate).toList();
        if (candidates.isEmpty()) {
            return promos;
        }
        var historyByProduct = loadHistoryByProduct(receipt, candidates);
        for (var item : candidates) {
            var historical = historyByProduct.getOrDefault(item.getProduct().getId(), List.of()).stream()
                    .filter(previous -> !previous.getId().equals(item.getId()))
                    .filter(previous -> !previous.isExcluded())
                    .filter(previous -> previous.getUnitPrice() != null)
                    .filter(previous -> wasBoughtBefore(previous, receipt))
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

    /** Personal notification — skips excluded and "not mine" lines (no promo alert for someone else's item). */
    private static boolean isPromoCandidate(ReceiptItem item) {
        return !item.isExcluded() && !item.isExcludedFromPersonal()
                && item.getProduct() != null && item.getUnitPrice() != null;
    }

    /** The household's purchase history of every candidate product, in ONE query. */
    private Map<UUID, List<ReceiptItem>> loadHistoryByProduct(Receipt receipt, List<ReceiptItem> candidates) {
        var productIds = candidates.stream().map(item -> item.getProduct().getId()).collect(Collectors.toSet());
        return receiptItemRepository
                .findHouseholdHistoryForProducts(productIds, receipt.getHousehold().getId()).stream()
                .collect(Collectors.groupingBy(previous -> previous.getProduct().getId()));
    }

    /**
     * The baseline is what the household paid BEFORE this purchase. Without this, scanning a
     * backlog of old notas compared each one against NEWER (inflated) prices and fired a false
     * "você economizou" per item. A nota with no issue date keeps the whole history.
     */
    private static boolean wasBoughtBefore(ReceiptItem previous, Receipt receipt) {
        var purchasedAt = receipt.getIssuedAt();
        var previousPurchasedAt = previous.getReceipt().getIssuedAt();
        if (purchasedAt == null || previousPurchasedAt == null) {
            return !previous.getReceipt().getId().equals(receipt.getId());
        }
        return previousPurchasedAt.isBefore(purchasedAt);
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
