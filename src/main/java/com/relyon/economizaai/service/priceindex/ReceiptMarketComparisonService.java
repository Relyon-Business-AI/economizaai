package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.config.ComparisonProperties;
import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse;
import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse.Item;
import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse.Market;
import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.ReceiptMarketComparison;
import com.relyon.economizaai.model.ReceiptMarketComparisonPrice;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptMarketComparisonRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.BasketLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.MarketAlternative;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * "Esta compra em outros mercados": what the items of one nota would have cost at nearby markets.
 *
 * <p>The other markets' prices are captured ONCE, when the nota is confirmed, and stored
 * ({@link ReceiptMarketComparison}). Later reads never price again, so the comparison of a
 * purchase does not move as prices change. The capture runs in the background right after the
 * confirmation commits; if it has not happened yet when the nota is read (still queued, or the
 * queue was full) the read does it on the spot, so the app never has to wait or poll.
 *
 * <p>What the household paid always comes from the nota's CURRENT items joined with the stored
 * prices: removing an item or fixing what was paid for it changes the comparison without
 * touching the stored prices. A nota bought too long ago
 * ({@link ComparisonProperties#getReceiptMaxAgeDays}) gets no comparison — today's prices say
 * nothing about a purchase that old.
 */
@Slf4j
@Service
public class ReceiptMarketComparisonService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptMarketComparisonRepository comparisonRepository;
    private final BasketComparisonService basketComparisonService;
    private final ViewerLocationService viewerLocationService;
    private final MarketNameService marketNameService;
    private final MarketLocationService marketLocationService;
    private final ComparisonProperties comparisonProperties;
    private final Executor comparisonExecutor;
    private final TransactionTemplate readTransaction;

    public ReceiptMarketComparisonService(ReceiptRepository receiptRepository,
                                          ReceiptMarketComparisonRepository comparisonRepository,
                                          BasketComparisonService basketComparisonService,
                                          ViewerLocationService viewerLocationService,
                                          MarketNameService marketNameService,
                                          MarketLocationService marketLocationService,
                                          ComparisonProperties comparisonProperties,
                                          @Qualifier(AsyncConfig.RECEIPT_COMPARISON_EXECUTOR) Executor comparisonExecutor,
                                          PlatformTransactionManager transactionManager) {
        this.receiptRepository = receiptRepository;
        this.comparisonRepository = comparisonRepository;
        this.basketComparisonService = basketComparisonService;
        this.viewerLocationService = viewerLocationService;
        this.marketNameService = marketNameService;
        this.marketLocationService = marketLocationService;
        this.comparisonProperties = comparisonProperties;
        this.comparisonExecutor = comparisonExecutor;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * Confirm hook: once the confirmation COMMITS, captures the comparison in the background.
     * Never blocks or fails the confirmation — a full queue or an error only means the comparison
     * is captured on the first read instead.
     */
    public void captureAfterCommit(UUID receiptId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            captureInBackground(receiptId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                captureInBackground(receiptId);
            }
        });
    }

    private void captureInBackground(UUID receiptId) {
        try {
            comparisonExecutor.execute(() -> captureSafely(receiptId));
        } catch (RejectedExecutionException queueFull) {
            log.warn("receipt.market_comparison.capture_rejected reason=queue_full");
        }
    }

    private void captureSafely(UUID receiptId) {
        try {
            // Read what the capture needs in one short read-only transaction (the nota's owner and
            // their position are lazy); the pricing and the write then run in their own.
            var input = readTransaction.execute(status -> receiptRepository.findByIdWithItemsAndProducts(receiptId)
                    .filter(receipt -> !comparisonRepository.existsByReceiptId(receiptId))
                    .map(receipt -> new CaptureInput(receipt, viewerLocationService.resolve(receipt.getUser()))));
            if (input != null && input.isPresent()) {
                capture(input.get().receipt(), input.get().location());
            }
        } catch (RuntimeException failure) {
            log.warn("receipt.market_comparison.capture_failed reason={}", failure.getMessage());
        }
    }

    private record CaptureInput(Receipt receipt, ViewerLocation location) {}

    /** The comparison of a nota of the household, captured now if it was not yet. */
    public ReceiptMarketComparisonResponse compare(User user, UUID receiptId) {
        var receipt = loadOwned(user, receiptId);
        if (receipt.getStatus() != ReceiptStatus.CONFIRMED) {
            return ReceiptMarketComparisonResponse.unavailable(receiptId);
        }
        var basket = comparableBasket(receipt);
        var stored = comparisonRepository.findByReceiptIdWithPrices(receiptId);
        if (stored.isPresent()) return toResponse(user, receipt, basket, stored.get());
        if (!isRecentPurchase(receipt)) return ReceiptMarketComparisonResponse.unavailable(receiptId);

        var location = viewerLocationService.resolve(user);
        if (!location.hasCoordinates()) {
            // Nothing is stored without a position: once the person sets one, the next read captures it.
            return new ReceiptMarketComparisonResponse(receiptId, true, null, basket.size(), false, List.of());
        }
        return capture(receipt, location)
                .map(captured -> toResponse(user, receipt, basket, captured))
                .orElseGet(() -> ReceiptMarketComparisonResponse.unavailable(receiptId));
    }

    private Receipt loadOwned(User user, UUID receiptId) {
        var receipt = receiptRepository.findByIdWithItemsAndProducts(receiptId)
                .orElseThrow(ReceiptNotFoundException::new);
        if (!receipt.getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ReceiptNotFoundException();
        }
        return receipt;
    }

    /**
     * Prices the nota at nearby markets and stores the result. Empty when the nota is not eligible
     * (not confirmed, bought too long ago, or no position to measure "nearby" from). When two
     * captures race, the loser returns what the winner stored.
     */
    private Optional<ReceiptMarketComparison> capture(Receipt receipt, ViewerLocation location) {
        if (receipt.getStatus() != ReceiptStatus.CONFIRMED || !isRecentPurchase(receipt) || !location.hasCoordinates()) {
            return Optional.empty();
        }
        var boughtAt = PriceIndexService.cnpjRoot(receipt.getCnpjEmitente());
        var alternatives = basketComparisonService.compare(location, comparableBasket(receipt),
                boughtAt == null ? Set.of() : Set.of(boughtAt), comparisonProperties.getAlternativeMarkets());
        var comparison = ReceiptMarketComparison.builder()
                .receiptId(receipt.getId())
                .computedAt(OffsetDateTime.now())
                .build();
        for (var position = 0; position < alternatives.size(); position++) {
            addPrices(comparison, alternatives.get(position), position);
        }
        try {
            var saved = comparisonRepository.saveAndFlush(comparison);
            log.info("receipt.market_comparison.captured markets={} prices={}", alternatives.size(),
                    saved.getPrices().size());
            return Optional.of(saved);
        } catch (DataIntegrityViolationException capturedByAnotherThread) {
            return comparisonRepository.findByReceiptIdWithPrices(receipt.getId());
        }
    }

    private static void addPrices(ReceiptMarketComparison comparison, MarketAlternative alternative, int position) {
        var distanceKm = alternative.distanceKm() == null ? null
                : BigDecimal.valueOf(alternative.distanceKm()).setScale(3, RoundingMode.HALF_UP);
        alternative.lines().forEach(line -> comparison.addPrice(ReceiptMarketComparisonPrice.builder()
                .marketPosition(position)
                .marketCnpj(alternative.cnpj())
                .marketName(alternative.marketName())
                .distanceKm(distanceKm)
                .productId(line.productId())
                .unitPrice(line.alternativeUnitPrice())
                .observedAt(line.observedAt())
                .build()));
    }

    /** Bought within the last few days, by purchase date (or by when it entered the app, without one). */
    private boolean isRecentPurchase(Receipt receipt) {
        var purchasedAt = receipt.getIssuedAt() != null ? receipt.getIssuedAt() : receipt.getCreatedAt();
        var oldestCompared = BrazilClock.nowDateTime().minusDays(comparisonProperties.getReceiptMaxAgeDays());
        return purchasedAt == null || !purchasedAt.isBefore(oldestCompared);
    }

    /** The nota's current items that can be compared, one line per product. */
    private static List<BasketLine> comparableBasket(Receipt receipt) {
        return BasketComparisonService.mergeByProduct(receipt.getItems().stream()
                .filter(ReceiptMarketComparisonService::isComparable)
                .map(ReceiptMarketComparisonService::toBasketLine)
                .toList());
    }

    /** A line can be compared when we know which product it is and what was paid for it. */
    private static boolean isComparable(ReceiptItem item) {
        return !item.isExcluded()
                && item.getProduct() != null
                && item.getQuantity() != null && item.getQuantity().signum() > 0
                && paidTotal(item) != null;
    }

    /** What the household really paid for the line: the manually informed price when there is one. */
    private static BigDecimal paidTotal(ReceiptItem item) {
        return item.getPaidTotalPrice() != null ? item.getPaidTotalPrice() : item.getTotalPrice();
    }

    private static BasketLine toBasketLine(ReceiptItem item) {
        var productName = item.getFriendlyDescription() != null && !item.getFriendlyDescription().isBlank()
                ? item.getFriendlyDescription()
                : item.getProduct().getNormalizedName();
        return new BasketLine(item.getProduct().getId(), productName, item.getQuantity(), paidTotal(item));
    }

    /**
     * The stored prices joined with the nota as it is now. A market that, after edits to the nota,
     * no longer covers the minimum share of its items is left out.
     */
    private ReceiptMarketComparisonResponse toResponse(User user, Receipt receipt, List<BasketLine> basket,
                                                       ReceiptMarketComparison comparison) {
        var minMatchedItems = comparisonProperties.minMatchedItems(basket.size());
        var pricesByMarket = comparison.getPrices().stream()
                .collect(Collectors.groupingBy(ReceiptMarketComparisonPrice::getMarketPosition));
        var marketPrices = pricesByMarket.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        var markets = readTransaction.execute(status -> {
            var cnpjs = marketPrices.stream().map(prices -> prices.get(0).getMarketCnpj()).toList();
            var overrides = marketNameService.resolveNames(user.getHousehold().getId(), cnpjs);
            var chainNames = marketNameService.resolveChainNames(cnpjs);
            var locations = marketLocationService.findByCnpjs(cnpjs);
            return marketPrices.stream()
                    .map(prices -> toMarket(prices, basket, overrides, chainNames,
                            locations.get(prices.get(0).getMarketCnpj())))
                    .filter(market -> market.matchedItemCount() >= minMatchedItems && market.matchedItemCount() > 0)
                    .toList();
        });
        return new ReceiptMarketComparisonResponse(receipt.getId(), true, comparison.getComputedAt(),
                basket.size(), true, markets);
    }

    private Market toMarket(List<ReceiptMarketComparisonPrice> prices, List<BasketLine> basket,
                            Map<String, String> overrides, Map<String, String> chainNames, MarketLocation location) {
        var market = prices.get(0);
        var priceByProduct = prices.stream().collect(Collectors.toMap(
                ReceiptMarketComparisonPrice::getProductId, price -> price, (first, ignored) -> first));
        var items = basket.stream()
                .filter(line -> priceByProduct.containsKey(line.productId()))
                .map(line -> toItem(line, priceByProduct.get(line.productId())))
                .sorted(Comparator.comparing(Item::productName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        var paidTotal = items.stream().map(Item::paidTotal).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        var alternativeTotal = items.stream().map(Item::alternativeTotal)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new Market(market.getMarketCnpj(), market.getMarketName(),
                marketNameService.friendlyName(overrides, chainNames, market.getMarketCnpj(), market.getMarketName()),
                chainLogoUrl(location),
                market.getDistanceKm() == null ? null : market.getDistanceKm().doubleValue(),
                items.size(), paidTotal, alternativeTotal, alternativeTotal.subtract(paidTotal), items);
    }

    private static Item toItem(BasketLine line, ReceiptMarketComparisonPrice price) {
        return new Item(line.productId(), line.productName(), line.quantity(),
                line.lineTotal().setScale(2, RoundingMode.HALF_UP), price.getUnitPrice(),
                price.getUnitPrice().multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP),
                price.getObservedAt());
    }

    private static String chainLogoUrl(MarketLocation location) {
        var chain = location == null ? null : location.getChain();
        if (chain == null || chain.getLogoUrl() == null) return null;
        return "/chains/logo/" + chain.getId() + "?v=" + chain.getLogoUrl().hashCode();
    }
}
