package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.model.GarimpoPriceSnapshot;
import com.relyon.economizaai.repository.GarimpoPriceSnapshotRepository;
import com.relyon.economizaai.service.ecommerce.ProviderProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns search results into the append-only price history: a snapshot is written only
 * when the product has none yet or its price changed since the latest one. Reads are
 * plain repository calls; the batch insert happens in one short TransactionTemplate
 * block (callers run this right after an outbound HTTP search — never inside a tx).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GarimpoSnapshotRecorder {

    private final GarimpoPriceSnapshotRepository snapshotRepository;
    private final TransactionTemplate transactionTemplate;

    public Optional<GarimpoPriceSnapshot> latestSnapshot(String provider, String externalId) {
        return snapshotRepository.findFirstByProviderAndExternalIdOrderByCreatedAtDesc(provider, externalId);
    }

    public Page<GarimpoPriceSnapshot> pageHistory(String provider, String externalId, Pageable pageable) {
        return snapshotRepository.findByProviderAndExternalIdOrderByCreatedAtDesc(provider, externalId, pageable);
    }

    /** Persists snapshots for the products whose price changed; returns how many were written. */
    public int recordChangedPrices(List<ProviderProduct> products) {
        var changedSnapshots = new ArrayList<GarimpoPriceSnapshot>();
        for (var product : products) {
            if (product.externalId() == null || product.price() == null) {
                continue;
            }
            var latest = latestSnapshot(product.providerKey(), product.externalId());
            var currentPrice = money(product.price());
            if (latest.isPresent() && latest.get().getPrice().compareTo(currentPrice) == 0) {
                continue;
            }
            changedSnapshots.add(toSnapshot(product, currentPrice));
        }
        if (changedSnapshots.isEmpty()) {
            return 0;
        }
        transactionTemplate.executeWithoutResult(status -> snapshotRepository.saveAll(changedSnapshots));
        return changedSnapshots.size();
    }

    private GarimpoPriceSnapshot toSnapshot(ProviderProduct product, BigDecimal currentPrice) {
        return GarimpoPriceSnapshot.builder()
                .provider(product.providerKey())
                .externalId(product.externalId())
                .title(truncate(product.title(), 512))
                .price(currentPrice)
                .originalPrice(product.originalPrice() == null ? null : money(product.originalPrice()))
                .discountPercent(product.discountPercent())
                .currency(product.currency() == null ? "BRL" : product.currency())
                .externalUrl(product.externalUrl())
                .affiliateUrl(product.affiliateUrl())
                .imageUrl(product.imageUrl())
                .sellerName(truncate(product.sellerName(), 255))
                .freeShipping(product.freeShipping())
                .build();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
