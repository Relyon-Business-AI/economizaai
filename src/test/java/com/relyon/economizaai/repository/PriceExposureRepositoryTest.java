package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class PriceExposureRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, Month.NOVEMBER, 16);

    @Autowired private PriceExposureRepository priceExposureRepository;

    private PriceExposure exposure(UUID userId, PriceExposureSurface surface, LocalDate shownOn) {
        return PriceExposure.builder()
                .userId(userId).householdId(UUID.randomUUID()).productId(UUID.randomUUID())
                .marketCnpj("12345678000199").unitPrice(new BigDecimal("7.50"))
                .surface(surface).subscriptionTier(SubscriptionTier.FREE)
                .shownOn(shownOn).shownAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void attributable_areTheHouseholdsUnconvertedExposuresOfThatProductAndMarketInTheWindowNewestFirst() {
        var householdId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var purchaseInstant = OffsetDateTime.now();
        var older = priceExposureRepository.save(attributableExposure(householdId, productId, purchaseInstant.minusDays(5)));
        var newer = priceExposureRepository.save(attributableExposure(householdId, productId, purchaseInstant.minusDays(1)));
        priceExposureRepository.save(attributableExposure(householdId, productId, purchaseInstant.minusDays(30)));
        priceExposureRepository.save(attributableExposure(householdId, productId, purchaseInstant.plusDays(1)));
        priceExposureRepository.save(attributableExposure(UUID.randomUUID(), productId, purchaseInstant.minusDays(1)));
        priceExposureRepository.save(attributableExposure(householdId, UUID.randomUUID(), purchaseInstant.minusDays(1)));
        var alreadyConverted = attributableExposure(householdId, productId, purchaseInstant.minusDays(2));
        alreadyConverted.setConvertedAt(purchaseInstant.minusDays(1));
        priceExposureRepository.save(alreadyConverted);

        var found = priceExposureRepository.findAttributable(householdId, productId, "12345678000199",
                purchaseInstant.minusDays(14), purchaseInstant);

        assertEquals(List.of(newer.getId(), older.getId()), found.stream().map(PriceExposure::getId).toList());
    }

    private PriceExposure attributableExposure(UUID householdId, UUID productId, OffsetDateTime shownAt) {
        var exposure = exposure(UUID.randomUUID(), PriceExposureSurface.BEST_MARKETS, shownAt.toLocalDate());
        exposure.setHouseholdId(householdId);
        exposure.setProductId(productId);
        exposure.setShownAt(shownAt);
        return exposure;
    }

    @Test
    void findsOnlyWhatThatScreenShowedThatPersonOnThatDay() {
        var userId = UUID.randomUUID();
        var expected = priceExposureRepository.save(exposure(userId, PriceExposureSurface.DEALS, TODAY));
        priceExposureRepository.save(exposure(userId, PriceExposureSurface.BEST_MARKETS, TODAY));
        priceExposureRepository.save(exposure(userId, PriceExposureSurface.DEALS, TODAY.minusDays(1)));
        priceExposureRepository.save(exposure(UUID.randomUUID(), PriceExposureSurface.DEALS, TODAY));

        var found = priceExposureRepository.findByUserIdAndSurfaceAndShownOn(userId, PriceExposureSurface.DEALS, TODAY);

        assertEquals(1, found.size());
        assertEquals(expected.getId(), found.get(0).getId());
    }
}
