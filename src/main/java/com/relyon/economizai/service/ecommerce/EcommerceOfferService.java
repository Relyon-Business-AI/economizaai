package com.relyon.economizai.service.ecommerce;

import com.relyon.economizai.config.EcommerceProperties;
import com.relyon.economizai.dto.response.EcommerceOfferResponse;
import com.relyon.economizai.model.EcommerceOffer;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.repository.EcommerceOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the best online offer for a scanned item: admin-CURATED offers first
 * (precision), plus any CONFIGURED providers' live matches, picking the cheapest
 * total (price + freight) in stock. Compares it to what the user paid to flag
 * "vale a pena online?". With no provider configured, it simply serves curated
 * offers — the whole feature degrades to precise, human-curated matches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcommerceOfferService {

    private final EcommerceOfferRepository offerRepository;
    private final EcommerceProperties properties;
    private final List<EcommerceProvider> providers;

    @Transactional(readOnly = true)
    public Optional<EcommerceOfferResponse> bestOfferForItem(ReceiptItem item, String cep) {
        var ean = eanOf(item);
        if (ean == null) {
            return Optional.empty();
        }
        var paidPrice = item.getPaidUnitPrice() != null ? item.getPaidUnitPrice() : item.getUnitPrice();

        var candidates = new ArrayList<Candidate>();
        for (var offer : offerRepository.findByEanAndActiveTrue(ean)) {
            candidates.add(Candidate.fromCurated(offer));
        }
        var effectiveCep = cep == null || cep.isBlank() ? properties.getDefaultCep() : cep;
        for (var provider : providers) {
            if (!provider.isConfigured()) {
                continue;
            }
            for (var providerOffer : provider.searchByEan(ean, effectiveCep)) {
                candidates.add(Candidate.fromProvider(providerOffer));
            }
        }

        var best = candidates.stream()
                .filter(Candidate::inStock)
                .filter(candidate -> candidate.price() != null)
                .min(Comparator.comparing(Candidate::total));
        best.ifPresent(candidate -> log.info("ecommerce.offer.best ean={} provider={} total={} curated={}",
                ean, candidate.provider(), candidate.total(), candidate.curated()));
        return best.map(candidate -> toResponse(candidate, paidPrice));
    }

    private EcommerceOfferResponse toResponse(Candidate best, BigDecimal paidPrice) {
        var total = best.total();
        var savings = paidPrice == null ? null : paidPrice.subtract(total).setScale(2, RoundingMode.HALF_UP);
        var worthIt = savings != null
                && savings.signum() > 0
                && savings.compareTo(properties.getWorthItMinSavings()) >= 0;
        return new EcommerceOfferResponse(best.provider(), best.title(), best.price(), best.freight(), total,
                best.currency(), best.externalUrl(), best.affiliateUrl(), best.imageUrl(), best.inStock(),
                best.curated(), worthIt, paidPrice, savings);
    }

    private static String eanOf(ReceiptItem item) {
        if (item.getEan() != null && !item.getEan().isBlank()) {
            return item.getEan();
        }
        return item.getProduct() != null ? item.getProduct().getEan() : null;
    }

    /** Common shape so curated rows and live provider offers compete on price. */
    private record Candidate(String provider, String title, BigDecimal price, BigDecimal freight,
                             String currency, String externalUrl, String affiliateUrl, String imageUrl,
                             boolean inStock, boolean curated) {

        BigDecimal total() {
            return freight == null ? price : price.add(freight);
        }

        static Candidate fromCurated(EcommerceOffer offer) {
            return new Candidate(offer.getProvider(), offer.getTitle(), offer.getPrice(), offer.getFreight(),
                    offer.getCurrency(), offer.getExternalUrl(), offer.getAffiliateUrl(), offer.getImageUrl(),
                    offer.isInStock(), true);
        }

        static Candidate fromProvider(ProviderOffer offer) {
            return new Candidate(offer.providerKey(), offer.title(), offer.price(), offer.freight(),
                    offer.currency(), offer.externalUrl(), offer.affiliateUrl(), offer.imageUrl(),
                    offer.inStock(), false);
        }
    }
}
