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
