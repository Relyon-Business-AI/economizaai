package com.relyon.economizaai.service.exposure;

import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import com.relyon.economizaai.repository.PriceExposureRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Logs the prices a screen showed a person ({@link PriceExposure}), so a later purchase can be
 * tied to what the app showed before it. Called from the READ path of the price screens, after
 * the response is built: best-effort, in its own short transaction, and it never propagates a
 * failure. At most one row per (user, product, market, surface, day) and a bounded number of
 * rows per call.
 */
@Slf4j
@Service
public class PriceExposureService {

    /** A single response never logs more than this many prices (a long list is not all seen). */
    static final int MAX_PRICES_PER_CALL = 60;

    private final PriceExposureRepository priceExposureRepository;
    private final TransactionTemplate exposureTransaction;

    public PriceExposureService(PriceExposureRepository priceExposureRepository,
                                PlatformTransactionManager transactionManager) {
        this.priceExposureRepository = priceExposureRepository;
        this.exposureTransaction = new TransactionTemplate(transactionManager);
        this.exposureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordShown(User user, PriceExposureSurface surface, List<ShownPrice> shownPrices) {
        if (user == null || user.getHousehold() == null || shownPrices == null || shownPrices.isEmpty()) {
            return;
        }
        try {
            var savedCount = exposureTransaction.execute(status -> saveNewExposures(user, surface, shownPrices));
            log.debug("price_exposure.recorded surface={} shown={} saved={}", surface, shownPrices.size(), savedCount);
        } catch (Exception recordFailure) {
            log.warn("price_exposure.record_failed surface={} reason={}", surface, recordFailure.toString());
        }
    }

    private int saveNewExposures(User user, PriceExposureSurface surface, List<ShownPrice> shownPrices) {
        var today = BrazilClock.today();
        var alreadyShownToday = shownKeysOf(user.getId(), surface, today);
        var newExposures = new ArrayList<PriceExposure>();
        for (var shownPrice : shownPrices) {
            if (newExposures.size() >= MAX_PRICES_PER_CALL) break;
            if (!shownPrice.isComplete()) continue;
            if (!alreadyShownToday.add(keyOf(shownPrice.productId(), shownPrice.marketCnpj()))) continue;
            newExposures.add(toExposure(user, surface, shownPrice, today));
        }
        priceExposureRepository.saveAll(newExposures);
        return newExposures.size();
    }

    private Set<String> shownKeysOf(UUID userId, PriceExposureSurface surface, LocalDate day) {
        var shownKeys = new HashSet<String>();
        priceExposureRepository.findByUserIdAndSurfaceAndShownOn(userId, surface, day)
                .forEach(exposure -> shownKeys.add(keyOf(exposure.getProductId(), exposure.getMarketCnpj())));
        return shownKeys;
    }

    private static PriceExposure toExposure(User user, PriceExposureSurface surface, ShownPrice shownPrice, LocalDate day) {
        return PriceExposure.builder()
                .userId(user.getId())
                .householdId(user.getHousehold().getId())
                .productId(shownPrice.productId())
                .marketCnpj(shownPrice.marketCnpj())
                .unitPrice(shownPrice.unitPrice().setScale(2, RoundingMode.HALF_UP))
                .surface(surface)
                .subscriptionTier(user.getSubscriptionTier())
                .shownOn(day)
                .shownAt(BrazilClock.nowOffset())
                .build();
    }

    private static String keyOf(UUID productId, String marketCnpj) {
        return productId + "|" + marketCnpj;
    }
}
