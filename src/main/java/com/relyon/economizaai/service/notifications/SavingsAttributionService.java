package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AppSavingSource;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.PriceExposureRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p><strong>Other screens.</strong> When no surfaced deal matches, the purchase can still be
 * credited to a price the app SHOWED for that product at that market — offers screen, best
 * markets, community promos, shopping plan, market screen or a price alert (see
 * {@link PriceExposure}). There the baseline is only the household's own previous purchase, and
 * the credit never exceeds the advantage the app showed: a price shown at or above what the
 * household already paid earns nothing. Each exposure is credited at most once.
 *
 * <p><strong>Following purchases.</strong> The first credited purchase of a product at a market
 * fixes the reference: what the household paid before the app showed the price. For
 * {@code followUpDays} after it, every purchase of that product at that market counts again
 * against that same reference (still capped by the price shown), so a household that keeps
 * buying at the cheaper price keeps being credited. A purchase at the reference price or above
 * counts nothing and deducts nothing: the total only grows.
 *
 * <p>Attribution is correlational, not proven causation — see DEV_NOTES.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsAttributionService {

    private final DealSurfaceStateRepository surfaceStateRepository;
    private final PriceExposureRepository priceExposureRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final UserRepository userRepository;
    private final NotificationEventRepository eventRepository;
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

        // issuedAt is Brazil-local wall clock (SEFAZ) — anchor it in the product zone.
        var purchaseInstant = receipt.getIssuedAt().atZone(BrazilClock.ZONE).toOffsetDateTime();
        var windowStart = purchaseInstant.minusDays(properties.getAttribution().getWindowDays());

        var conversions = 0;
        var savedTotal = BigDecimal.ZERO;
        for (var item : receipt.getItems()) {
            var saved = attributeItem(receipt, item, householdUserIds, windowStart, purchaseInstant);
            if (saved != null) {
                conversions++;
                savedTotal = savedTotal.add(saved);
            }
        }
        log.info("attribution.done receipt={} conversions={} savedTotal=R${}",
                abbrev(receipt), conversions, savedTotal.setScale(2, RoundingMode.HALF_UP));
    }

    /** Returns the realized savings when this item is credited to something the app showed, else null. */
    private BigDecimal attributeItem(Receipt receipt, ReceiptItem item,
                                     List<UUID> householdUserIds, OffsetDateTime windowStart,
                                     OffsetDateTime purchaseInstant) {
        if (item.isExcluded() || item.isExcludedFromPersonal() || item.getProduct() == null
                || item.getUnitPrice() == null || item.getQuantity() == null) {
            return null;
        }
        var earlierCredits = eventRepository.findConvertedForProductAtMarket(
                        householdUserIds, item.getProduct().getId(), receipt.getCnpjEmitente(),
                        purchaseInstant.minusDays(properties.getAttribution().getFollowUpDays()))
                .stream().map(CreditedPurchase::of).toList();
        if (earlierCredits.stream().anyMatch(credited -> credited.isOf(item.getId()))) {
            return null; // this line was already credited
        }
        var firstCreditedPurchase = earlierCredits.stream()
                .filter(credited -> credited.setsTheReferenceFor(
                        purchaseInstant, properties.getAttribution().getFollowUpDays()))
                .max(Comparator.comparing(CreditedPurchase::purchasedAt));
        if (firstCreditedPurchase.isPresent()) {
            return attributeAsFollowUp(receipt, item, firstCreditedPurchase.get(), windowStart, purchaseInstant);
        }

        var savedWithSurfacedDeal = attributeToSurfacedDeal(receipt, item, householdUserIds, windowStart, purchaseInstant);
        if (savedWithSurfacedDeal != null) return savedWithSurfacedDeal;
        return attributeToShownPrice(receipt, item, windowStart, purchaseInstant);
    }

    private BigDecimal attributeToSurfacedDeal(Receipt receipt, ReceiptItem item,
                                               List<UUID> householdUserIds, OffsetDateTime windowStart,
                                               OffsetDateTime purchaseInstant) {
        var productId = item.getProduct().getId();
        // Bounded on BOTH sides: within the attribution window AND surfaced at or
        // before the purchase — a deal aired after the buy earns no credit.
        var candidates = surfaceStateRepository.findAttributable(
                householdUserIds, productId, receipt.getCnpjEmitente(), windowStart, purchaseInstant);
        if (candidates.isEmpty()) return null;

        var surface = candidates.get(0); // newest qualifying surfacing
        var paidUnitPrice = item.getUnitPrice();
        var householdLastPaid = lastPaidBefore(receipt, productId);
        var previousLastPaid = householdLastPaid != null ? householdLastPaid : surface.getLastUnitPrice();
        if (previousLastPaid == null || previousLastPaid.compareTo(paidUnitPrice) <= 0) {
            return null; // no provable savings
        }

        var savings = previousLastPaid.subtract(paidUnitPrice)
                .multiply(item.getQuantity())
                .setScale(2, RoundingMode.HALF_UP);
        recordConversion(receipt, item, purchaseInstant,
                new Credit(savings, previousLastPaid, null, AppSavingSource.DEALS, false, householdLastPaid != null));

        surface.setConvertedAt(OffsetDateTime.now());
        surfaceStateRepository.save(surface);
        return savings;
    }

    /**
     * Credit for a price another screen (or a price alert) showed before the purchase. Counts
     * only up to the advantage shown: the unit saving is measured against the HIGHER of the
     * price paid and the price shown, so the app never takes credit for more than it showed.
     */
    private BigDecimal attributeToShownPrice(Receipt receipt, ReceiptItem item,
                                             OffsetDateTime windowStart, OffsetDateTime purchaseInstant) {
        var productId = item.getProduct().getId();
        var exposures = priceExposureRepository.findAttributable(
                receipt.getHousehold().getId(), productId, receipt.getCnpjEmitente(), windowStart, purchaseInstant);
        if (exposures.isEmpty()) return null;

        var exposure = exposures.get(0); // newest qualifying exposure
        var paidUnitPrice = item.getUnitPrice();
        var previousLastPaid = lastPaidBefore(receipt, productId);
        var creditedUnitPrice = paidUnitPrice.max(exposure.getUnitPrice());
        if (previousLastPaid == null || previousLastPaid.compareTo(creditedUnitPrice) <= 0) {
            return null; // nothing the household paid before, or the app showed no advantage
        }

        var savings = previousLastPaid.subtract(creditedUnitPrice)
                .multiply(item.getQuantity())
                .setScale(2, RoundingMode.HALF_UP);
        recordConversion(receipt, item, purchaseInstant, new Credit(savings, previousLastPaid,
                exposure.getUnitPrice(), AppSavingSource.of(exposure.getSurface()), false, true));

        exposure.setConvertedAt(OffsetDateTime.now());
        priceExposureRepository.save(exposure);
        return savings;
    }

    /**
     * Credit for buying again after a first credited purchase, measured against the price the
     * household paid before the app showed it. The cap is the best price the app showed: the one
     * of the first purchase or a newer one. A surfaced deal has no cap, as on its first purchase.
     */
    private BigDecimal attributeAsFollowUp(Receipt receipt, ReceiptItem item, CreditedPurchase firstCreditedPurchase,
                                           OffsetDateTime windowStart, OffsetDateTime purchaseInstant) {
        var paidUnitPrice = item.getUnitPrice();
        var shownUnitPrice = firstCreditedPurchase.shownUnitPrice() == null ? null
                : bestShownPrice(receipt, item, firstCreditedPurchase.shownUnitPrice(), windowStart, purchaseInstant);
        var creditedUnitPrice = shownUnitPrice == null ? paidUnitPrice : paidUnitPrice.max(shownUnitPrice);
        var referenceUnitPrice = firstCreditedPurchase.referenceUnitPrice();
        if (referenceUnitPrice.compareTo(creditedUnitPrice) <= 0) {
            return null; // back at the old price or above: counts nothing, deducts nothing
        }

        var savings = referenceUnitPrice.subtract(creditedUnitPrice)
                .multiply(item.getQuantity())
                .setScale(2, RoundingMode.HALF_UP);
        recordConversion(receipt, item, purchaseInstant,
                new Credit(savings, referenceUnitPrice, shownUnitPrice, firstCreditedPurchase.source(), true, true));
        return savings;
    }

    private BigDecimal bestShownPrice(Receipt receipt, ReceiptItem item, BigDecimal firstShownUnitPrice,
                                      OffsetDateTime windowStart, OffsetDateTime purchaseInstant) {
        var newerExposures = priceExposureRepository.findAttributable(receipt.getHousehold().getId(),
                item.getProduct().getId(), receipt.getCnpjEmitente(), windowStart, purchaseInstant);
        return newerExposures.isEmpty()
                ? firstShownUnitPrice
                : firstShownUnitPrice.min(newerExposures.get(0).getUnitPrice());
    }

    /**
     * What a purchase earned. The reference is the price the household paid before the app showed
     * it; {@code ownReference} is false when the household had never bought the product and the
     * surfaced deal's baseline stood in, which is not a reference to keep for later purchases.
     */
    private record Credit(BigDecimal savings, BigDecimal referenceUnitPrice, BigDecimal shownUnitPrice,
                          AppSavingSource source, boolean followUp, boolean ownReference) {
    }

    private void recordConversion(Receipt receipt, ReceiptItem item, OffsetDateTime purchaseInstant, Credit credit) {
        var productId = item.getProduct().getId();
        var paidUnitPrice = item.getUnitPrice();
        var discountFraction = credit.referenceUnitPrice().subtract(paidUnitPrice)
                .divide(credit.referenceUnitPrice(), 4, RoundingMode.HALF_UP);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("savingsAmount", credit.savings());
        metadata.put("paidUnitPrice", paidUnitPrice);
        metadata.put(CreditedPurchase.REFERENCE_UNIT_PRICE, credit.referenceUnitPrice());
        metadata.put("discountFraction", discountFraction);
        metadata.put(CreditedPurchase.SOURCE, credit.source().name());
        metadata.put(CreditedPurchase.FOLLOW_UP, credit.followUp());
        metadata.put(CreditedPurchase.OWN_REFERENCE, credit.ownReference());
        metadata.put(CreditedPurchase.PURCHASED_AT, purchaseInstant.toString());
        if (credit.shownUnitPrice() != null) metadata.put(CreditedPurchase.SHOWN_UNIT_PRICE, credit.shownUnitPrice());
        if (item.getId() != null) metadata.put(CreditedPurchase.RECEIPT_ITEM_ID, item.getId().toString());
        eventService.record(receipt.getUser(), NotificationEventType.CONVERTED, RecordContext.builder()
                .productId(productId)
                .marketCnpj(receipt.getCnpjEmitente())
                .channel("ATTRIBUTION")
                .savingsAmount(credit.savings())
                .metadata(metadata)
                .build());
        log.info("attribution.converted product={} market={} source={} followUp={} savings=R${} paid={} previous={}",
                productId, receipt.getCnpjEmitente(), credit.source(), credit.followUp(), credit.savings(),
                paidUnitPrice, credit.referenceUnitPrice());
    }

    /**
     * The household's last paid unit price for this product on a confirmed receipt
     * issued strictly before {@code receipt}; null when it never bought it before.
     */
    private BigDecimal lastPaidBefore(Receipt receipt, UUID productId) {
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
        return latestPriorPrice;
    }

    private static String abbrev(Receipt receipt) {
        return receipt.getId() == null ? "" : receipt.getId().toString().substring(0, 8);
    }
}
