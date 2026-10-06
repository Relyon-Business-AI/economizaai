package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.dto.request.MerchantPromoRequest;
import com.relyon.economizaai.dto.response.MerchantPromoResponse;
import com.relyon.economizaai.exception.MerchantAccessNotFoundException;
import com.relyon.economizaai.exception.MerchantPromoInvalidException;
import com.relyon.economizaai.exception.MerchantPromoNotFoundException;
import com.relyon.economizaai.exception.MerchantPromoOverlapException;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.MerchantPromo;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantPromoSource;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Merchant promo CRUD (docs/MERCHANT_ACCOUNTS.md, Fase 2). Every operation is
 * scoped to the chains granted to the logged-in MERCHANT user, and every WRITE
 * passes the marketing-subscription gate. Announced promos live in their own
 * table and never touch the collaborative price index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantPromoService {

    private final MerchantPromoRepository promoRepository;
    private final MerchantAccessRepository merchantAccessRepository;
    private final MerchantSubscriptionService merchantSubscriptionService;
    private final ProductRepository productRepository;
    private final PriceObservationRepository observationRepository;

    @Transactional(readOnly = true)
    public Page<MerchantPromoResponse> list(User merchantUser, Pageable pageable) {
        var chains = grantedChains(merchantUser);
        if (chains.isEmpty()) {
            return Page.empty(pageable);
        }
        return promoRepository.findAllByCnpjRootInOrderByEndsAtDesc(chains, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public MerchantPromoResponse create(User merchantUser, MerchantPromoRequest request,
                                        MerchantPromoSource source) {
        var cnpjRoot = resolveChain(merchantUser, request.cnpjRoot());
        merchantSubscriptionService.requirePublishing(cnpjRoot);
        var promo = buildValidated(merchantUser, cnpjRoot, request, source, null);
        var saved = promoRepository.save(promo);
        log.info("merchant.promo.created promo={} cnpjRoot={} ean={} price={} source={}",
                saved.getId(), cnpjRoot, saved.getEan(), saved.getPromoPrice(), source);
        return toResponse(saved);
    }

    @Transactional
    public MerchantPromoResponse update(User merchantUser, UUID promoId, MerchantPromoRequest request) {
        var promo = requireOwnPromo(merchantUser, promoId);
        merchantSubscriptionService.requirePublishing(promo.getCnpjRoot());
        var validated = buildValidated(merchantUser, promo.getCnpjRoot(), request, promo.getSource(), promoId);
        promo.setEan(validated.getEan());
        promo.setProduct(validated.getProduct());
        promo.setDescription(validated.getDescription());
        promo.setPromoPrice(validated.getPromoPrice());
        promo.setRegularPrice(validated.getRegularPrice());
        promo.setStartsAt(validated.getStartsAt());
        promo.setEndsAt(validated.getEndsAt());
        var saved = promoRepository.save(promo);
        log.info("merchant.promo.updated promo={} cnpjRoot={}", promoId, promo.getCnpjRoot());
        return toResponse(saved);
    }

    @Transactional
    public void delete(User merchantUser, UUID promoId) {
        var promo = requireOwnPromo(merchantUser, promoId);
        promoRepository.delete(promo);
        log.info("merchant.promo.deleted promo={} cnpjRoot={}", promoId, promo.getCnpjRoot());
    }

    /**
     * Shared row validation (manual, import and batch): price scale + range,
     * date order, EAN shape, duplicate-window guard, EAN→canonical-product match.
     */
    MerchantPromo buildValidated(User merchantUser, String cnpjRoot, MerchantPromoRequest request,
                                 MerchantPromoSource source, UUID excludePromoId) {
        var ean = request.ean() == null ? "" : request.ean().trim();
        if (!ean.matches("\\d{8,14}")) {
            throw new MerchantPromoInvalidException("merchant.promo.invalid.ean");
        }
        if (request.promoPrice() == null || request.promoPrice().signum() <= 0) {
            throw new MerchantPromoInvalidException("merchant.promo.invalid.price");
        }
        if (request.startsAt() == null || request.endsAt() == null
                || request.endsAt().isBefore(request.startsAt())) {
            throw new MerchantPromoInvalidException("merchant.promo.invalid.dates");
        }
        if (promoRepository.existsOverlapping(cnpjRoot, ean, request.startsAt(), request.endsAt(), excludePromoId)) {
            throw new MerchantPromoOverlapException(ean);
        }
        return MerchantPromo.builder()
                .cnpjRoot(cnpjRoot)
                .createdByUserId(merchantUser.getId())
                .product(productRepository.findByEan(ean).orElse(null))
                .ean(ean)
                .description(request.description())
                .promoPrice(request.promoPrice().setScale(2, RoundingMode.HALF_UP))
                .regularPrice(request.regularPrice() == null
                        ? null : request.regularPrice().setScale(2, RoundingMode.HALF_UP))
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .source(source)
                .build();
    }

    /**
     * Resolves which chain a write targets: explicit cnpjRoot must be granted;
     * omitted is allowed only for single-chain accounts (the common case).
     */
    String resolveChain(User merchantUser, String requestedCnpjRoot) {
        var chains = grantedChains(merchantUser);
        if (requestedCnpjRoot != null && !requestedCnpjRoot.isBlank()) {
            if (!chains.contains(requestedCnpjRoot)) {
                throw new MerchantAccessNotFoundException(requestedCnpjRoot);
            }
            return requestedCnpjRoot;
        }
        if (chains.size() != 1) {
            throw new MerchantPromoInvalidException("merchant.promo.invalid.chain");
        }
        return chains.get(0);
    }

    public List<String> grantedChains(User merchantUser) {
        return merchantAccessRepository.findAllByUserId(merchantUser.getId()).stream()
                .map(MerchantAccess::getCnpjRoot)
                .distinct()
                .toList();
    }

    private MerchantPromo requireOwnPromo(User merchantUser, UUID promoId) {
        var chains = grantedChains(merchantUser);
        if (chains.isEmpty()) {
            throw new MerchantPromoNotFoundException();
        }
        return promoRepository.findByIdAndCnpjRootIn(promoId, chains)
                .orElseThrow(MerchantPromoNotFoundException::new);
    }

    private MerchantPromoResponse toResponse(MerchantPromo promo) {
        return MerchantPromoResponse.from(promo, isVerifiedByReceipts(promo));
    }

    /** A real scanned receipt at the chain, inside the window, at (or under) the announced price. */
    public boolean isVerifiedByReceipts(MerchantPromo promo) {
        if (promo.getProduct() == null) {
            return false;
        }
        var tolerance = promo.getPromoPrice().multiply(new BigDecimal("1.01"));
        return observationRepository.existsConfirmingObservation(
                promo.getProduct().getId(),
                promo.getCnpjRoot(),
                promo.getStartsAt().atStartOfDay(),
                promo.getEndsAt().plusDays(1).atStartOfDay(),
                tolerance);
    }
}
