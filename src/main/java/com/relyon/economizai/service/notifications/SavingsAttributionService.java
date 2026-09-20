package com.relyon.economizai.service.notifications;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.model.DealSurfaceState;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.NotificationEventType;
import com.relyon.economizai.repository.DealSurfaceStateRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.notifications.NotificationEventService.RecordContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase D — closes the notifications telemetry loop by attributing realized R$
 * savings to deals we surfaced. After a receipt is confirmed and its price
 * observations are recorded, this best-effort pass looks for purchases that
 * match a recently-surfaced, still-un-attributed {@link DealSurfaceState} for a
 * user in the buyer's household. Each match becomes a {@code CONVERTED}
 * telemetry event carrying the realized savings (the product's north-star
 * metric: "R$ saved attributable to notifications").
 *
 * <p><strong>User resolution:</strong> a digest could go to any household
 * member, so we attribute against the surface rows of <em>every</em> user in the
 * buyer's household (not only the buyer). The matched surface row(s) are stamped
 * {@code convertedAt} so a single surfacing is attributed at most once; a later
 * re-surface clears it and lets the same deal be attributed again.
 *
 * <p><strong>Savings formula</strong> (counted only when positive):
 * {@code (previousLastPaid - paidUnitPrice) * quantity}, where
 * {@code previousLastPaid} is the household's last paid unit price for that
 * product on a confirmed receipt issued strictly BEFORE this one (so the very
 * receipt being attributed never counts as its own baseline). When there's no
 * prior purchase we fall back to the surfaced deal's {@code lastUnitPrice}
 * baseline, but only when that baseline is higher than what was paid — otherwise
 * there's no provable savings and we skip.
 *
 * <p>Attribution is correlational, not proven causation — see DEV_NOTES.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsAttributionService {

    private final DealSurfaceStateRepository surfaceStateRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final UserRepository userRepository;
    private final NotificationEventService eventService;
    private final CollaborativeProperties properties;

    /**
     * Runs in its OWN transaction (REQUIRES_NEW), not the caller's. confirm()
     * invokes this as best-effort analytics and catches any failure — but if we
     * joined confirm's transaction, a failure here would mark it rollback-only and
     * confirm's commit would blow up with UnexpectedRollbackException despite the
     * catch. Isolating attribution means a failed pass rolls back only its own
     * work; confirm commits regardless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attribute(Receipt receipt) {
        var cnpj = receipt.getCnpjEmitente();
        if (cnpj == null || cnpj.isBlank() || receipt.getIssuedAt() == null) {
            return;
        }
        var householdId = receipt.getHousehold().getId();
        var householdUserIds = userRepository.findAllByHouseholdId(householdId).stream()
                .map(householdUser -> householdUser.getId())
                .toList();
        if (householdUserIds.isEmpty()) return;

        var purchaseInstant = receipt.getIssuedAt().atOffset(ZoneOffset.UTC);
        var windowStart = purchaseInstant.minusDays(properties.getAttribution().getWindowDays());

        var conversions = 0;
        var savedTotal = BigDecimal.ZERO;
        for (var item : receipt.getItems()) {
            var saved = attributeItem(receipt, item, householdUserIds, windowStart);
            if (saved != null) {
                conversions++;
                savedTotal = savedTotal.add(saved);
            }
        }
        log.info("attribution.done receipt={} conversions={} savedTotal=R${}",
                abbrev(receipt), conversions, savedTotal.setScale(2, RoundingMode.HALF_UP));
    }

    /** Returns the realized savings when this item converted a surfaced deal, else null. */
    private BigDecimal attributeItem(Receipt receipt, ReceiptItem item,
                                     List<UUID> householdUserIds, OffsetDateTime windowStart) {
        if (item.isExcluded() || item.isExcludedFromPersonal() || item.getProduct() == null
                || item.getUnitPrice() == null || item.getQuantity() == null) {
            return null;
        }
        var productId = item.getProduct().getId();
        var candidates = surfaceStateRepository.findAttributable(
                householdUserIds, productId, receipt.getCnpjEmitente(), windowStart);
        if (candidates.isEmpty()) return null;

        var surface = candidates.get(0); // newest qualifying surfacing
        var paidUnitPrice = item.getUnitPrice();
        var previousLastPaid = previousLastPaid(receipt, productId, surface);
        if (previousLastPaid == null || previousLastPaid.compareTo(paidUnitPrice) <= 0) {
            return null; // no provable savings
        }

        var savings = previousLastPaid.subtract(paidUnitPrice)
                .multiply(item.getQuantity())
                .setScale(2, RoundingMode.HALF_UP);
        var discountFraction = previousLastPaid.subtract(paidUnitPrice)
                .divide(previousLastPaid, 4, RoundingMode.HALF_UP);

        eventService.record(receipt.getUser(), NotificationEventType.CONVERTED, RecordContext.builder()
                .productId(productId)
                .marketCnpj(receipt.getCnpjEmitente())
                .channel("ATTRIBUTION")
                .savingsAmount(savings)
                .metadata(Map.of(
                        "savingsAmount", savings,
                        "paidUnitPrice", paidUnitPrice,
                        "previousLastPaid", previousLastPaid,
                        "discountFraction", discountFraction))
                .build());

        surface.setConvertedAt(OffsetDateTime.now());
        surfaceStateRepository.save(surface);
        log.info("attribution.converted product={} market={} savings=R${} paid={} previous={}",
                productId, receipt.getCnpjEmitente(), savings, paidUnitPrice, previousLastPaid);
        return savings;
    }

    /**
     * The household's last paid unit price for this product on a confirmed receipt
     * issued strictly before {@code receipt}; falls back to the surfaced deal's
     * baseline ({@code lastUnitPrice}) when there's no prior purchase.
     */
    private BigDecimal previousLastPaid(Receipt receipt, UUID productId, DealSurfaceState surface) {
        var history = receiptItemRepository.findHouseholdHistoryForProduct(
                productId, receipt.getHousehold().getId());
        BigDecimal latestPriorPrice = null;
        LocalDateTime latestPriorIssuedAt = null;
        for (var historyItem : history) {
            var issuedAt = historyItem.getReceipt().getIssuedAt();
            if (issuedAt == null || !issuedAt.isBefore(receipt.getIssuedAt())) continue;
            if (historyItem.getUnitPrice() == null) continue;
            if (latestPriorIssuedAt == null || issuedAt.isAfter(latestPriorIssuedAt)) {
                latestPriorIssuedAt = issuedAt;
                latestPriorPrice = historyItem.getUnitPrice();
            }
        }
        if (latestPriorPrice != null) return latestPriorPrice;
        return surface.getLastUnitPrice();
    }

    private static String abbrev(Receipt receipt) {
        return receipt.getId() == null ? "" : receipt.getId().toString().substring(0, 8);
    }
}
