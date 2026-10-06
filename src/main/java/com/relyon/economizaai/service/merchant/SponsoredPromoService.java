package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.SponsoredPromoResponse;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.MerchantPromo;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ONLY consumer touchpoint of merchant promos — and it ships INERT:
 * {@code economizaai.merchant.promos-feed-enabled} defaults to false, so the
 * endpoint returns [] until we decide to flip it. Structural guardrails
 * (MONETIZATION.md §4): sponsored promos live on their OWN endpoint, are never
 * merged into the organic community-promo list, and never touch any
 * "cheapest" ranking. Only chains with an ACTIVE marketing subscription appear.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SponsoredPromoService {

    private final MerchantPromoRepository promoRepository;
    private final MerchantSubscriptionService merchantSubscriptionService;
    private final MarketLocationRepository marketLocationRepository;
    private final CollaborativeProperties properties;
    private final MerchantPromoService merchantPromoService;

    @Transactional(readOnly = true)
    public List<SponsoredPromoResponse> currentSponsoredPromos() {
        if (!properties.getMerchant().isPromosFeedEnabled()) {
            return List.of();
        }
        var today = BrazilClock.today();
        var livePromos = promoRepository.findLiveOn(today);
        if (livePromos.isEmpty()) {
            return List.of();
        }
        var payingChains = activeChains(livePromos);
        var chainNames = chainDisplayNames(payingChains);
        var feed = livePromos.stream()
                .filter(promo -> payingChains.contains(promo.getCnpjRoot()))
                .sorted(Comparator.comparing(MerchantPromo::getEndsAt))
                .map(promo -> toResponse(promo, chainNames.get(promo.getCnpjRoot())))
                .toList();
        log.info("merchant.sponsored_feed.served promos={} chains={}", feed.size(), payingChains.size());
        return feed;
    }

    private Set<String> activeChains(List<MerchantPromo> livePromos) {
        var today = BrazilClock.today();
        var chains = livePromos.stream().map(MerchantPromo::getCnpjRoot).distinct().toList();
        return merchantSubscriptionService.forChains(chains).stream()
                .filter(subscription -> subscription.isActiveOn(today))
                .map(subscription -> subscription.getCnpjRoot())
                .collect(Collectors.toSet());
    }

    /** Friendly chain label: the name of any known store of the chain (best-effort). */
    private Map<String, String> chainDisplayNames(Set<String> chains) {
        return chains.stream()
                .map(cnpjRoot -> Map.entry(cnpjRoot, marketLocationRepository.findAllByCnpjRoot(cnpjRoot).stream()
                        .map(MarketLocation::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .findFirst().orElse("")))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
    }

    private SponsoredPromoResponse toResponse(MerchantPromo promo, String chainName) {
        return new SponsoredPromoResponse(
                promo.getId(),
                promo.getCnpjRoot(),
                chainName,
                promo.getEan(),
                promo.getProduct() == null ? null : promo.getProduct().getId(),
                promo.getProduct() == null ? null : promo.getProduct().getNormalizedName(),
                promo.getDescription(),
                promo.getPromoPrice(),
                promo.getRegularPrice(),
                promo.getStartsAt(),
                promo.getEndsAt(),
                merchantPromoService.isVerifiedByReceipts(promo));
    }
}
