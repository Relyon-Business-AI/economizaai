package com.relyon.economizaai.service.exposure;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.PriceExposureRepository;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceExposureServiceTest {

    private static final String MARKET_CNPJ = "12345678000199";

    @Mock private PriceExposureRepository priceExposureRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Captor private ArgumentCaptor<List<PriceExposure>> savedExposures;

    private PriceExposureService priceExposureService;
    private User user;

    @BeforeEach
    void setUp() {
        priceExposureService = new PriceExposureService(priceExposureRepository, transactionManager);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("EXPO01").build();
        user = User.builder().id(UUID.randomUUID()).email("exposure-test@economizaai.app")
                .household(household).subscriptionTier(SubscriptionTier.PRO).build();
    }

    private List<PriceExposure> recordAndCapture(PriceExposureSurface surface, List<ShownPrice> shownPrices) {
        priceExposureService.recordShown(user, surface, shownPrices);
        verify(priceExposureRepository).saveAll(savedExposures.capture());
        return savedExposures.getValue();
    }

    @Test
    void savesOneRowPerShownPriceWithTheContextOfTheMoment() {
        var productId = UUID.randomUUID();

        var saved = recordAndCapture(PriceExposureSurface.DEALS,
                List.of(new ShownPrice(productId, MARKET_CNPJ, new BigDecimal("7.499"))));

        assertEquals(1, saved.size());
        var exposure = saved.get(0);
        assertEquals(user.getId(), exposure.getUserId());
        assertEquals(user.getHousehold().getId(), exposure.getHouseholdId());
        assertEquals(productId, exposure.getProductId());
        assertEquals(MARKET_CNPJ, exposure.getMarketCnpj());
        assertEquals(new BigDecimal("7.50"), exposure.getUnitPrice());
        assertEquals(PriceExposureSurface.DEALS, exposure.getSurface());
        assertEquals(SubscriptionTier.PRO, exposure.getSubscriptionTier());
        assertEquals(BrazilClock.today(), exposure.getShownOn());
    }

    @Test
    void skipsWhatTheSameScreenAlreadyShowedToday() {
        var alreadyShownProduct = UUID.randomUUID();
        var newProduct = UUID.randomUUID();
        when(priceExposureRepository.findByUserIdAndSurfaceAndShownOn(
                user.getId(), PriceExposureSurface.BEST_MARKETS, BrazilClock.today()))
                .thenReturn(List.of(PriceExposure.builder()
                        .productId(alreadyShownProduct).marketCnpj(MARKET_CNPJ).build()));

        var saved = recordAndCapture(PriceExposureSurface.BEST_MARKETS, List.of(
                new ShownPrice(alreadyShownProduct, MARKET_CNPJ, new BigDecimal("5.00")),
                new ShownPrice(newProduct, MARKET_CNPJ, new BigDecimal("6.00"))));

        assertEquals(1, saved.size());
        assertEquals(newProduct, saved.get(0).getProductId());
    }

    @Test
    void savesARepeatedPairOnlyOnceInTheSameCall() {
        var productId = UUID.randomUUID();

        var saved = recordAndCapture(PriceExposureSurface.COMMUNITY_PROMOS, List.of(
                new ShownPrice(productId, MARKET_CNPJ, new BigDecimal("5.00")),
                new ShownPrice(productId, MARKET_CNPJ, new BigDecimal("5.00"))));

        assertEquals(1, saved.size());
    }

    @Test
    void leavesOutPricesWithoutProductMarketOrPositiveValue() {
        var saved = recordAndCapture(PriceExposureSurface.MARKET_PRICES, List.of(
                new ShownPrice(null, MARKET_CNPJ, new BigDecimal("5.00")),
                new ShownPrice(UUID.randomUUID(), null, new BigDecimal("5.00")),
                new ShownPrice(UUID.randomUUID(), " ", new BigDecimal("5.00")),
                new ShownPrice(UUID.randomUUID(), MARKET_CNPJ, null),
                new ShownPrice(UUID.randomUUID(), MARKET_CNPJ, BigDecimal.ZERO)));

        assertTrue(saved.isEmpty());
    }

    @Test
    void capsTheRowsSavedByASingleCall() {
        var manyPrices = IntStream.range(0, PriceExposureService.MAX_PRICES_PER_CALL + 25)
                .mapToObj(index -> new ShownPrice(UUID.randomUUID(), MARKET_CNPJ, new BigDecimal("3.00")))
                .toList();

        var saved = recordAndCapture(PriceExposureSurface.MARKET_PRICES, manyPrices);

        assertEquals(PriceExposureService.MAX_PRICES_PER_CALL, saved.size());
    }

    @Test
    void doesNothingForAnEmptyListOrAUserWithoutHousehold() {
        priceExposureService.recordShown(user, PriceExposureSurface.DEALS, List.of());
        priceExposureService.recordShown(User.builder().id(UUID.randomUUID()).build(), PriceExposureSurface.DEALS,
                List.of(new ShownPrice(UUID.randomUUID(), MARKET_CNPJ, new BigDecimal("5.00"))));

        verifyNoInteractions(priceExposureRepository);
    }

    @Test
    void neverPropagatesAStorageFailureToTheScreen() {
        when(priceExposureRepository.findByUserIdAndSurfaceAndShownOn(any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> priceExposureService.recordShown(user, PriceExposureSurface.DEALS,
                List.of(new ShownPrice(UUID.randomUUID(), MARKET_CNPJ, new BigDecimal("5.00")))));
    }
}
