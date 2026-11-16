package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.dto.response.AppSavingPurchaseResponse;
import com.relyon.economizaai.dto.response.AppSavingsResponse;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AppSavingSource;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Com a ajuda do app": what a household saved in purchases that came out cheaper than its
 * previous one after the app showed that price (the {@code CONVERTED} events written by
 * {@code SavingsAttributionService}). Read-only over the household's own telemetry — nothing
 * from other households is involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppSavingsService {

    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationEventRepository eventRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasService householdProductAliasService;
    private final MarketLocationService marketLocationService;
    private final MarketNameService marketNameService;
    private final SubscriptionGateService subscriptionGate;
    private final LocalizedMessageService messageService;

    @Transactional(readOnly = true)
    public AppSavingsResponse summary(User user, LocalDateTime requestedFrom, LocalDateTime requestedTo) {
        var period = periodOf(user, requestedFrom, requestedTo);
        var cheaperPurchases = cheaperPurchasesOf(user, period);
        var saved = cheaperPurchases.stream().map(CheaperPurchase::saved).reduce(BigDecimal.ZERO, BigDecimal::add);
        var largestSaving = cheaperPurchases.isEmpty() ? BigDecimal.ZERO : cheaperPurchases.get(0).saved();
        log.debug("app_savings.summary household={} purchases={} saved={}",
                user.getHousehold().getId(), cheaperPurchases.size(), saved);
        return new AppSavingsResponse(period.from(), period.to(), saved, cheaperPurchases.size(), largestSaving);
    }

    /** The purchases behind the summary, biggest saving first, one page at a time. */
    @Transactional(readOnly = true)
    public Page<AppSavingPurchaseResponse> purchases(User user, LocalDateTime requestedFrom, LocalDateTime requestedTo,
                                                     int requestedPage, int requestedSize) {
        var pageRequest = PageRequest.of(Math.max(requestedPage, 0), Math.clamp(requestedSize, 1, MAX_PAGE_SIZE));
        var cheaperPurchases = cheaperPurchasesOf(user, periodOf(user, requestedFrom, requestedTo));
        var purchasesOnPage = cheaperPurchases.stream()
                .skip(pageRequest.getOffset())
                .limit(pageRequest.getPageSize())
                .toList();
        return new PageImpl<>(toResponses(user, purchasesOnPage), pageRequest, cheaperPurchases.size());
    }

    private record Period(LocalDateTime from, LocalDateTime to) {}

    /** FREE history window first; no start asked for means everything on record. */
    private Period periodOf(User user, LocalDateTime requestedFrom, LocalDateTime requestedTo) {
        var to = requestedTo != null ? requestedTo : BrazilClock.nowDateTime();
        return new Period(subscriptionGate.clampFrom(user, requestedFrom), to);
    }

    private List<CheaperPurchase> cheaperPurchasesOf(User user, Period period) {
        var householdUserIds = userRepository.findAllByHouseholdId(user.getHousehold().getId()).stream()
                .map(User::getId)
                .toList();
        if (householdUserIds.isEmpty()) return List.of();
        return eventRepository.findConvertedBetween(householdUserIds, inBrazil(period.from()), inBrazil(period.to())).stream()
                .map(CheaperPurchase::of)
                .sorted(CheaperPurchase.BIGGEST_SAVING_FIRST)
                .toList();
    }

    private static OffsetDateTime inBrazil(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(BrazilClock.ZONE).toOffsetDateTime();
    }

    private List<AppSavingPurchaseResponse> toResponses(User user, List<CheaperPurchase> cheaperPurchases) {
        if (cheaperPurchases.isEmpty()) return List.of();
        var householdId = user.getHousehold().getId();
        var productIds = cheaperPurchases.stream().map(CheaperPurchase::productId).collect(Collectors.toSet());
        var marketCnpjs = cheaperPurchases.stream().map(CheaperPurchase::marketCnpj).filter(Objects::nonNull).distinct().toList();

        var householdProductNames = householdProductAliasService.resolvedNamesFor(householdId, productIds);
        var catalogProductNames = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getNormalizedName));
        var storesByCnpj = marketLocationService.findByCnpjs(marketCnpjs);
        var nameOverrides = marketNameService.resolveNames(householdId, marketCnpjs);
        var chainNames = marketNameService.resolveChainNames(marketCnpjs);
        var sourceLabel = messageService.translate("savings.source." + AppSavingSource.DEALS.name());

        return cheaperPurchases.stream()
                .map(cheaperPurchase -> toResponse(cheaperPurchase,
                        productNameOf(cheaperPurchase.productId(), householdProductNames, catalogProductNames),
                        storesByCnpj.get(cheaperPurchase.marketCnpj()), nameOverrides, chainNames, sourceLabel))
                .toList();
    }

    private static String productNameOf(UUID productId, Map<UUID, String> householdProductNames,
                                        Map<UUID, String> catalogProductNames) {
        var householdName = householdProductNames.get(productId);
        return householdName != null ? householdName : catalogProductNames.get(productId);
    }

    private AppSavingPurchaseResponse toResponse(CheaperPurchase cheaperPurchase, String productName, MarketLocation store,
                                                 Map<String, String> nameOverrides, Map<String, String> chainNames,
                                                 String sourceLabel) {
        var marketName = store != null ? store.getName() : null;
        return new AppSavingPurchaseResponse(
                cheaperPurchase.eventId(),
                cheaperPurchase.productId(),
                productName,
                cheaperPurchase.marketCnpj(),
                marketName,
                marketNameService.friendlyName(nameOverrides, chainNames, cheaperPurchase.marketCnpj(), marketName),
                AppSavingSource.DEALS,
                sourceLabel,
                cheaperPurchase.paidPrice(),
                cheaperPurchase.previousPrice(),
                cheaperPurchase.saved(),
                cheaperPurchase.percent(),
                cheaperPurchase.recordedAt().atZoneSameInstant(BrazilClock.ZONE).toLocalDateTime());
    }
}
