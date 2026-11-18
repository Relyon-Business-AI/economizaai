package com.relyon.economizaai.service.shopping;

import com.relyon.economizaai.dto.request.RecordShoppingTripRequest;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ShoppingTrip;
import com.relyon.economizaai.model.ShoppingTripAlternative;
import com.relyon.economizaai.model.ShoppingTripItem;
import com.relyon.economizaai.model.ShoppingTripStop;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ShoppingTripRepository;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.priceindex.BasketComparisonService;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.BasketLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.MarketAlternative;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores the record of a finished shopping trip (plan vs what happened). The data is the
 * household's own and is kept for later analysis only — nothing reads it back to the app.
 *
 * <p>The app may send the same trip more than once (it retries until the call succeeds), so the
 * device-generated {@code clientTripId} makes the call idempotent. References the client cannot
 * be trusted on are checked here: a nota is linked only when it belongs to the household, and
 * items of products that no longer exist are left out.
 *
 * <p>Along with the trip it stores what the items BOUGHT (checked off) would have cost at up to
 * three single nearby markets — the baseline for "was the route worth it". A market the trip
 * stopped at counts as an alternative too (buying everything there), unless it was the only stop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingTripRecorder {

    private final ShoppingTripRepository tripRepository;
    private static final int MAX_ALTERNATIVE_MARKETS = 3;

    private final ReceiptRepository receiptRepository;
    private final ProductRepository productRepository;
    private final BasketComparisonService basketComparisonService;
    private final ViewerLocationService viewerLocationService;

    /**
     * Saves the trip and returns its id; a trip this household already sent returns the stored id.
     * Not transactional on purpose: the price comparison only reads, and a failure there must not
     * take the trip record down with it. The trip and its children are written by one save.
     */
    public UUID record(User user, RecordShoppingTripRequest request) {
        var householdId = user.getHousehold().getId();
        var alreadyRecorded = tripRepository.findByHouseholdIdAndClientTripId(householdId, request.clientTripId());
        if (alreadyRecorded.isPresent()) {
            log.debug("shopping_trip.record.duplicate household={} trip={}", householdId, alreadyRecorded.get().getId());
            return alreadyRecorded.get().getId();
        }

        var householdReceiptIds = householdReceiptIds(householdId, request);
        var knownProductIds = knownProductIds(request);
        var trip = toTrip(user, request);
        for (var position = 0; position < request.stops().size(); position++) {
            trip.addStop(toStop(request.stops().get(position), position, householdReceiptIds, knownProductIds));
        }
        singleMarketAlternatives(user, trip).forEach(trip::addAlternative);
        var saved = tripRepository.save(trip);
        log.info("shopping_trip.recorded household={} trip={} outcome={} stops={} items={} with_receipt={} "
                        + "alternatives={} planned_total={}",
                householdId, saved.getId(), saved.getOutcome(), saved.getStops().size(),
                saved.getStops().stream().mapToInt(stop -> stop.getItems().size()).sum(),
                saved.getStops().stream().filter(stop -> stop.getReceiptId() != null).count(),
                saved.getAlternatives().size(), saved.getPlannedTotal());
        return saved.getId();
    }

    /**
     * What the bought items would have cost at each of up to three single nearby markets. Empty
     * when nothing was bought, the shopper has no known position, or the comparison fails — the
     * trip is recorded either way.
     */
    private List<ShoppingTripAlternative> singleMarketAlternatives(User user, ShoppingTrip trip) {
        var boughtLines = trip.getStops().stream()
                .flatMap(stop -> stop.getItems().stream())
                .filter(item -> item.getStatus() == ShoppingTripItemStatus.IN_CART)
                .filter(item -> unitPricePaidOrPlanned(item) != null)
                .map(item -> new BasketLine(item.getProductId(), null, item.getQuantity(),
                        unitPricePaidOrPlanned(item).multiply(item.getQuantity()).setScale(2, RoundingMode.HALF_UP)))
                .toList();
        if (boughtLines.isEmpty()) return List.of();
        var boughtItemCount = (int) boughtLines.stream().map(BasketLine::productId).distinct().count();
        try {
            return basketComparisonService
                    .compare(viewerLocationService.resolve(user), boughtLines, onlyStopChain(trip),
                            MAX_ALTERNATIVE_MARKETS)
                    .stream()
                    .map(alternative -> toAlternative(alternative, boughtItemCount))
                    .toList();
        } catch (RuntimeException comparisonFailure) {
            log.warn("shopping_trip.alternatives_failed household={} items={}", trip.getHouseholdId(),
                    boughtItemCount, comparisonFailure);
            return List.of();
        }
    }

    /** The price the item was bought at: the one on the nota when known, else the predicted one. */
    private static BigDecimal unitPricePaidOrPlanned(ShoppingTripItem item) {
        return item.getPaidUnitPrice() != null ? item.getPaidUnitPrice() : item.getPlannedUnitPrice();
    }

    /** A single-stop trip was already "everything at one market": that market is not an alternative. */
    private static Set<String> onlyStopChain(ShoppingTrip trip) {
        if (trip.getStops().size() != 1) return Set.of();
        var onlyStopRoot = PriceIndexService.cnpjRoot(trip.getStops().get(0).getMarketCnpj());
        return onlyStopRoot == null ? Set.of() : Set.of(onlyStopRoot);
    }

    private static ShoppingTripAlternative toAlternative(MarketAlternative alternative, int boughtItemCount) {
        return ShoppingTripAlternative.builder()
                .marketCnpj(alternative.cnpj())
                .marketName(alternative.marketName())
                .distanceKm(alternative.distanceKm() == null ? null
                        : BigDecimal.valueOf(alternative.distanceKm()).setScale(3, RoundingMode.HALF_UP))
                .matchedItemCount(alternative.matchedItemCount())
                .boughtItemCount(boughtItemCount)
                .basketTotal(alternative.basketTotal())
                .alternativeTotal(alternative.alternativeTotal())
                .build();
    }

    /** Notas referenced by the trip that really belong to this household. */
    private Set<UUID> householdReceiptIds(UUID householdId, RecordShoppingTripRequest request) {
        var referencedIds = request.stops().stream()
                .map(RecordShoppingTripRequest.Stop::receiptId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (referencedIds.isEmpty()) return Set.of();
        return receiptRepository.findAllById(referencedIds).stream()
                .filter(receipt -> receipt.getHousehold().getId().equals(householdId))
                .map(Receipt::getId)
                .collect(Collectors.toSet());
    }

    /** Products referenced by the trip that still exist (a product may have been merged away). */
    private Set<UUID> knownProductIds(RecordShoppingTripRequest request) {
        var referencedIds = request.stops().stream()
                .flatMap(stop -> stop.items().stream())
                .map(RecordShoppingTripRequest.Item::productId)
                .collect(Collectors.toSet());
        if (referencedIds.isEmpty()) return Set.of();
        return productRepository.findAllById(referencedIds).stream()
                .map(Product::getId)
                .collect(Collectors.toSet());
    }

    private ShoppingTrip toTrip(User user, RecordShoppingTripRequest request) {
        return ShoppingTrip.builder()
                .userId(user.getId())
                .householdId(user.getHousehold().getId())
                .shoppingListId(request.shoppingListId())
                .clientTripId(request.clientTripId())
                .startedAt(request.startedAt())
                .finishedAt(request.finishedAt())
                .outcome(request.outcome())
                .maxDistanceKm(request.maxDistanceKm())
                .maxStops(request.maxStops())
                .minSavingPerStop(money(request.minSavingPerStop()))
                .plannedTotal(money(request.plannedTotal()))
                .plannedStopCount(request.plannedStopCount())
                .unpricedItemCount(orZero(request.unpricedItemCount()))
                .skippedSavingTotal(request.skippedSavingTotal() == null
                        ? BigDecimal.ZERO.setScale(2) : money(request.skippedSavingTotal()))
                .priceLookupCount(orZero(request.priceLookupCount()))
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private ShoppingTripStop toStop(RecordShoppingTripRequest.Stop requested, int position,
                                    Set<UUID> householdReceiptIds, Set<UUID> knownProductIds) {
        var receiptLinked = requested.receiptId() != null && householdReceiptIds.contains(requested.receiptId());
        var stop = ShoppingTripStop.builder()
                .position(position)
                .marketCnpj(requested.marketCnpj())
                .marketName(requested.marketName())
                .distanceKm(requested.distanceKm())
                .concluded(requested.concluded())
                .concludedAt(requested.concludedAt())
                .plannedTotal(plannedTotal(requested, knownProductIds))
                .receiptId(receiptLinked ? requested.receiptId() : null)
                .receiptPaidTotal(receiptLinked ? money(requested.receiptPaidTotal()) : null)
                .receiptPredictedTotal(receiptLinked ? money(requested.receiptPredictedTotal()) : null)
                .build();
        requested.items().stream()
                .filter(item -> knownProductIds.contains(item.productId()))
                .map(item -> toItem(item, receiptLinked))
                .forEach(stop::addItem);
        return stop;
    }

    /** Predicted total of the stop, from its items (items with no predicted price count as zero). */
    private BigDecimal plannedTotal(RecordShoppingTripRequest.Stop requested, Set<UUID> knownProductIds) {
        return requested.items().stream()
                .filter(item -> knownProductIds.contains(item.productId()) && item.plannedUnitPrice() != null)
                .map(item -> item.plannedUnitPrice().multiply(item.quantity()).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private ShoppingTripItem toItem(RecordShoppingTripRequest.Item requested, boolean receiptLinked) {
        var inReceipt = receiptLinked && Boolean.TRUE.equals(requested.inReceipt());
        return ShoppingTripItem.builder()
                .productId(requested.productId())
                .quantity(requested.quantity())
                .plannedUnitPrice(money(requested.plannedUnitPrice()))
                .priceSource(requested.priceSource())
                .status(requested.status())
                .inReceipt(inReceipt)
                .paidUnitPrice(inReceipt ? money(requested.paidUnitPrice()) : null)
                .plannedStopPosition(requested.plannedStopPosition())
                .addedDuringTrip(Boolean.TRUE.equals(requested.addedDuringTrip()))
                .build();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
