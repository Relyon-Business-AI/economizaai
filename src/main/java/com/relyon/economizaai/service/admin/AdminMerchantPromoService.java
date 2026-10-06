package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.MerchantPromoResponse;
import com.relyon.economizaai.exception.MerchantPromoNotFoundException;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.service.merchant.MerchantPromoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Moderation of merchant-announced promos: the admin can inspect any chain's
 * promos and pull one from circulation (active=false) without deleting the
 * merchant's record — abuse control for the sponsored feed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMerchantPromoService {

    private final MerchantPromoRepository promoRepository;
    private final MerchantPromoService merchantPromoService;

    @Transactional(readOnly = true)
    public Page<MerchantPromoResponse> list(String cnpjRoot, Pageable pageable) {
        var page = cnpjRoot == null || cnpjRoot.isBlank()
                ? promoRepository.findAll(pageable)
                : promoRepository.findAllByCnpjRootOrderByEndsAtDesc(cnpjRoot, pageable);
        return page.map(promo -> MerchantPromoResponse.from(promo, merchantPromoService.isVerifiedByReceipts(promo)));
    }

    @Transactional
    public MerchantPromoResponse setActive(UUID promoId, boolean active) {
        var promo = promoRepository.findById(promoId).orElseThrow(MerchantPromoNotFoundException::new);
        promo.setActive(active);
        promoRepository.save(promo);
        log.info("admin.merchant_promo.set_active promo={} active={}", promoId, active);
        return MerchantPromoResponse.from(promo, merchantPromoService.isVerifiedByReceipts(promo));
    }
}
