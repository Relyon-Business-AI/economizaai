package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.CuratedOfferRequest;
import com.relyon.economizai.dto.response.CuratedOfferResponse;
import com.relyon.economizai.exception.EcommerceOfferNotFoundException;
import com.relyon.economizai.model.EcommerceOffer;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.repository.EcommerceOfferRepository;
import com.relyon.economizai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Admin curation of e-commerce offers — the precision-first MVP: a human maps an EAN
 * to a specific online product with its price + (affiliate) link. These curated rows
 * are served by {@link com.relyon.economizai.service.ecommerce.EcommerceOfferService}
 * before/alongside any live provider result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminEcommerceService {

    private final EcommerceOfferRepository offerRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Page<CuratedOfferResponse> list(String ean, Pageable pageable) {
        var trimmed = Optional.ofNullable(ean).map(String::trim).filter(value -> !value.isBlank()).orElse(null);
        var page = trimmed == null
                ? offerRepository.findAllByOrderByUpdatedAtDesc(pageable)
                : offerRepository.findByEanOrderByUpdatedAtDesc(trimmed, pageable);
        return page.map(CuratedOfferResponse::from);
    }

    @Transactional
    public CuratedOfferResponse create(CuratedOfferRequest request, String adminEmail) {
        var offer = EcommerceOffer.builder()
                .ean(request.ean().trim())
                .product(resolveProduct(request.productId()))
                .provider(request.provider().trim())
                .title(request.title().trim())
                .externalUrl(request.externalUrl())
                .affiliateUrl(request.affiliateUrl())
                .imageUrl(request.imageUrl())
                .price(request.price())
                .freight(request.freight())
                .currency(currencyOrDefault(request.currency()))
                .inStock(request.inStock() == null || request.inStock())
                .active(request.active() == null || request.active())
                .curated(true)
                .curatedBy(adminEmail)
                .build();
        var saved = offerRepository.save(offer);
        log.info("admin.ecommerce.offer_created id={} ean={} provider={} by={}",
                saved.getId(), saved.getEan(), saved.getProvider(), adminEmail);
        return CuratedOfferResponse.from(saved);
    }

    @Transactional
    public CuratedOfferResponse update(UUID id, CuratedOfferRequest request, String adminEmail) {
        var offer = offerRepository.findById(id)
                .orElseThrow(() -> new EcommerceOfferNotFoundException(id.toString()));
        offer.setEan(request.ean().trim());
        offer.setProduct(resolveProduct(request.productId()));
        offer.setProvider(request.provider().trim());
        offer.setTitle(request.title().trim());
        offer.setExternalUrl(request.externalUrl());
        offer.setAffiliateUrl(request.affiliateUrl());
        offer.setImageUrl(request.imageUrl());
        offer.setPrice(request.price());
        offer.setFreight(request.freight());
        offer.setCurrency(currencyOrDefault(request.currency()));
        offer.setInStock(request.inStock() == null || request.inStock());
        offer.setActive(request.active() == null || request.active());
        offer.setCuratedBy(adminEmail);
        var saved = offerRepository.save(offer);
        log.info("admin.ecommerce.offer_updated id={} ean={} by={}", saved.getId(), saved.getEan(), adminEmail);
        return CuratedOfferResponse.from(saved);
    }

    @Transactional
    public void delete(UUID id) {
        if (!offerRepository.existsById(id)) {
            throw new EcommerceOfferNotFoundException(id.toString());
        }
        offerRepository.deleteById(id);
        log.info("admin.ecommerce.offer_deleted id={}", id);
    }

    private Product resolveProduct(UUID productId) {
        return productId == null ? null : productRepository.findById(productId).orElse(null);
    }

    private static String currencyOrDefault(String currency) {
        return currency == null || currency.isBlank() ? "BRL" : currency.trim().toUpperCase();
    }
}
