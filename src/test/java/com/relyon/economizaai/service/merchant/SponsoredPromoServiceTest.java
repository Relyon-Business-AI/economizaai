package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MerchantPromo;
import com.relyon.economizaai.model.MerchantSubscription;
import com.relyon.economizaai.model.enums.MerchantPromoSource;
import com.relyon.economizaai.model.enums.MerchantSubscriptionStatus;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guardrail suite for the ONLY consumer touchpoint: the sponsored feed is
 * inert by default (flag off → []), serves only paying chains, and lives on a
 * dedicated endpoint so it can never alter the organic promo/cheapest ranking.
 */
@ExtendWith(MockitoExtension.class)
class SponsoredPromoServiceTest {

    private static final String PAYING_CHAIN = "93015006";
    private static final String EXPIRED_CHAIN = "11111111";

    @Mock private MerchantPromoRepository promoRepository;
    @Mock private MerchantSubscriptionService merchantSubscriptionService;
    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private MerchantPromoService merchantPromoService;

    private CollaborativeProperties properties;
    private SponsoredPromoService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new SponsoredPromoService(promoRepository, merchantSubscriptionService,
                marketLocationRepository, properties, merchantPromoService);
    }

    private MerchantPromo livePromo(String cnpjRoot) {
        return MerchantPromo.builder()
                .id(UUID.randomUUID()).cnpjRoot(cnpjRoot).ean("7891000100103")
                .promoPrice(new BigDecimal("4.99"))
                .startsAt(BrazilClock.today().minusDays(1))
                .endsAt(BrazilClock.today().plusDays(5))
                .source(MerchantPromoSource.MANUAL)
                .build();
    }

    private MerchantSubscription subscription(String cnpjRoot, MerchantSubscriptionStatus status) {
        return MerchantSubscription.builder().cnpjRoot(cnpjRoot).status(status)
                .freeUntil(status == MerchantSubscriptionStatus.PROMO ? BrazilClock.today().plusDays(30) : null)
                .activatedAt(LocalDateTime.now()).build();
    }

    @Test
    void feed_flagOff_returnsEmptyAndNeverQueriesPromos() {
        properties.getMerchant().setPromosFeedEnabled(false);

        assertThat(service.currentSponsoredPromos()).isEmpty();
        verify(promoRepository, never()).findLiveOn(any());
    }

    @Test
    void feed_flagOn_servesOnlyPayingChains() {
        properties.getMerchant().setPromosFeedEnabled(true);
        when(promoRepository.findLiveOn(any())).thenReturn(
                List.of(livePromo(PAYING_CHAIN), livePromo(EXPIRED_CHAIN)));
        when(merchantSubscriptionService.forChains(anyList())).thenReturn(List.of(
                subscription(PAYING_CHAIN, MerchantSubscriptionStatus.PROMO),
                subscription(EXPIRED_CHAIN, MerchantSubscriptionStatus.EXPIRED)));
        lenient().when(marketLocationRepository.findAllByCnpjRoot(any())).thenReturn(List.of());

        var feed = service.currentSponsoredPromos();

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).cnpjRoot()).isEqualTo(PAYING_CHAIN);
    }

    @Test
    void feed_flagOn_mapsVerifiedFlag() {
        properties.getMerchant().setPromosFeedEnabled(true);
        var promo = livePromo(PAYING_CHAIN);
        when(promoRepository.findLiveOn(any())).thenReturn(List.of(promo));
        when(merchantSubscriptionService.forChains(anyList())).thenReturn(
                List.of(subscription(PAYING_CHAIN, MerchantSubscriptionStatus.ACTIVE)));
        when(marketLocationRepository.findAllByCnpjRoot(PAYING_CHAIN)).thenReturn(List.of());
        when(merchantPromoService.isVerifiedByReceipts(promo)).thenReturn(true);

        var feed = service.currentSponsoredPromos();

        assertThat(feed.get(0).verified()).isTrue();
    }
}
