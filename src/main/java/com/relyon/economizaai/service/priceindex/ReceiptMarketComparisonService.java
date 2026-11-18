package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse;
import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse.Item;
import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse.Market;
import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.AlternativeLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.BasketLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.MarketAlternative;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compares one nota of the household with nearby markets: what the same items would have cost
 * at each of them. See {@link BasketComparisonService} for which markets qualify.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptMarketComparisonService {

    /** Markets offered per nota. */
    static final int MAX_MARKETS = 3;

    private final ReceiptRepository receiptRepository;
    private final BasketComparisonService basketComparisonService;
    private final ViewerLocationService viewerLocationService;
    private final MarketNameService marketNameService;
    private final MarketLocationService marketLocationService;

    @Transactional(readOnly = true)
    public ReceiptMarketComparisonResponse compare(User user, UUID receiptId) {
        var receipt = loadOwned(user, receiptId);
        var basketLines = receipt.getItems().stream()
                .filter(ReceiptMarketComparisonService::isComparable)
                .map(ReceiptMarketComparisonService::toBasketLine)
                .toList();
        var comparableItemCount = (int) basketLines.stream().map(BasketLine::productId).distinct().count();
        var location = viewerLocationService.resolve(user);
        var boughtAt = PriceIndexService.cnpjRoot(receipt.getCnpjEmitente());
        var alternatives = basketComparisonService.compare(location, basketLines,
                boughtAt == null ? Set.of() : Set.of(boughtAt), MAX_MARKETS);
        log.info("receipt.market_comparison comparable_items={} markets={} location={}",
                comparableItemCount, alternatives.size(), location.source());
        return new ReceiptMarketComparisonResponse(receipt.getId(), comparableItemCount,
                location.hasCoordinates(), toMarkets(user.getHousehold().getId(), alternatives));
    }

    private Receipt loadOwned(User user, UUID receiptId) {
        var receipt = receiptRepository.findByIdWithItemsAndProducts(receiptId)
                .orElseThrow(ReceiptNotFoundException::new);
        if (!receipt.getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ReceiptNotFoundException();
        }
        return receipt;
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

    private List<Market> toMarkets(UUID householdId, List<MarketAlternative> alternatives) {
        if (alternatives.isEmpty()) return List.of();
        var cnpjs = alternatives.stream().map(MarketAlternative::cnpj).toList();
        var overrides = marketNameService.resolveNames(householdId, cnpjs);
        var chainNames = marketNameService.resolveChainNames(cnpjs);
        var locations = marketLocationService.findByCnpjs(cnpjs);
        return alternatives.stream()
                .map(alternative -> toMarket(alternative, overrides, chainNames, locations.get(alternative.cnpj())))
                .toList();
    }

    private Market toMarket(MarketAlternative alternative, Map<String, String> overrides,
                            Map<String, String> chainNames, MarketLocation location) {
        return new Market(alternative.cnpj(), alternative.marketName(),
                marketNameService.friendlyName(overrides, chainNames, alternative.cnpj(), alternative.marketName()),
                chainLogoUrl(location), alternative.distanceKm(), alternative.matchedItemCount(),
                alternative.basketTotal(), alternative.alternativeTotal(), alternative.difference(),
                alternative.lines().stream().map(ReceiptMarketComparisonService::toItem).toList());
    }

    private static String chainLogoUrl(MarketLocation location) {
        var chain = location == null ? null : location.getChain();
        if (chain == null || chain.getLogoUrl() == null) return null;
        return "/chains/logo/" + chain.getId() + "?v=" + chain.getLogoUrl().hashCode();
    }

    private static Item toItem(AlternativeLine line) {
        return new Item(line.productId(), line.productName(), line.quantity(), line.basketLineTotal(),
                line.alternativeUnitPrice(), line.alternativeLineTotal(), line.observedAt());
    }
}
