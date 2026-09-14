package com.relyon.economizai.service.analytics;

import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.model.enums.Platform;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.repository.MetaAdSpendRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.analytics.meta.MetaAdsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private MetaAdSpendRepository metaAdSpendRepository;
    @Mock private MetaAdsProperties metaAdsProperties;

    @InjectMocks private AdminAnalyticsService service;

    @BeforeEach
    void defaultStubs() {
        lenient().when(userRepository.signupTimelineSince(any(), anyBoolean())).thenReturn(List.of());
        lenient().when(userRepository.campaignBreakdownSince(any(), anyBoolean())).thenReturn(List.of());
        lenient().when(userRepository.platformBreakdownSince(any(), anyBoolean())).thenReturn(List.of());
        lenient().when(metaAdSpendRepository.campaignTotalsBetween(any(), any())).thenReturn(List.of());
        lenient().when(metaAdSpendRepository.totalSpendBetween(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void funnelComputesCountsAndRates() {
        when(userRepository.countSignupsSince(any(), anyBoolean())).thenReturn(10L);
        when(userRepository.countVerifiedSince(any(), anyBoolean())).thenReturn(5L);
        when(userRepository.countActivatedSince(any(), anyBoolean())).thenReturn(2L);
        when(userRepository.countProTierSince(any(), anyBoolean())).thenReturn(1L);
        when(userRepository.channelBreakdownSince(any(), anyBoolean())).thenReturn(List.of());
        when(metaAdsProperties.isConfigured()).thenReturn(false);

        var report = service.acquisition(30, false);

        assertThat(report.funnel().signups()).isEqualTo(10L);
        assertThat(report.funnel().verifiedRate()).isEqualTo(0.5d);
        assertThat(report.funnel().activatedRate()).isEqualTo(0.2d);
        assertThat(report.windowDays()).isEqualTo(30);
    }

    @Test
    void adSpendNotConfiguredCarriesHintAndZeroSpend() {
        stubEmptyFunnel();
        when(userRepository.channelBreakdownSince(any(), anyBoolean())).thenReturn(List.of());
        when(metaAdsProperties.isConfigured()).thenReturn(false);

        var report = service.acquisition(7, false);

        assertThat(report.adSpend().configured()).isFalse();
        assertThat(report.adSpend().totalSpend()).isEqualByComparingTo("0.00");
        assertThat(report.adSpend().note()).contains("META_ADS_TOKEN");
    }

    @Test
    void costPerSignupUsesPaidChannelSignups() {
        stubEmptyFunnel();
        // channel row: INSTAGRAM_PAID with 4 signups, 3 verified, 1 pro
        Object[] channelRow = {AcquisitionChannel.INSTAGRAM_PAID, 4L, 3L, 1L};
        when(userRepository.channelBreakdownSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(channelRow));
        when(metaAdsProperties.isConfigured()).thenReturn(true);
        when(metaAdSpendRepository.totalSpendBetween(any(), any())).thenReturn(new BigDecimal("200.00"));
        when(userRepository.countActivatedSince(any(), anyBoolean())).thenReturn(0L);

        var report = service.acquisition(30, false);

        assertThat(report.adSpend().paidSignups()).isEqualTo(4L);
        assertThat(report.adSpend().costPerSignup()).isEqualByComparingTo("50.00");
        assertThat(report.byChannel()).hasSize(1);
        assertThat(report.byChannel().get(0).channel()).isEqualTo("INSTAGRAM_PAID");
    }

    @Test
    void subscriptionsFlagAllPromoWhenNoPayers() {
        Object[] free = {SubscriptionTier.FREE, 3L};
        Object[] pro = {SubscriptionTier.PRO, 7L};
        when(userRepository.tierDistribution(anyBoolean())).thenReturn(List.of(free, pro));
        when(subscriptionRepository.countPaying(eq(SubscriptionStatus.ACTIVE), anyBoolean())).thenReturn(0L);
        when(subscriptionRepository.countPromoGranted(eq(SubscriptionStatus.ACTIVE), anyBoolean())).thenReturn(7L);

        var report = service.subscriptions(false);

        assertThat(report.totalUsers()).isEqualTo(10L);
        assertThat(report.byTier()).containsEntry("PRO", 7L);
        assertThat(report.payingActive()).isZero();
        assertThat(report.note()).contains("promo");
    }

    @Test
    void platformBreakdownMapsNullToUnknown() {
        stubEmptyFunnel();
        when(userRepository.channelBreakdownSince(any(), anyBoolean())).thenReturn(List.of());
        Object[] web = {Platform.WEB, 3L};
        Object[] unknown = {null, 2L};
        when(userRepository.platformBreakdownSince(any(), anyBoolean()))
                .thenReturn(List.<Object[]>of(web, unknown));
        when(metaAdsProperties.isConfigured()).thenReturn(false);

        var report = service.acquisition(30, false);

        assertThat(report.byPlatform()).extracting("platform").containsExactly("WEB", "UNKNOWN");
    }

    private void stubEmptyFunnel() {
        when(userRepository.countSignupsSince(any(), anyBoolean())).thenReturn(0L);
        lenient().when(userRepository.countVerifiedSince(any(), anyBoolean())).thenReturn(0L);
        lenient().when(userRepository.countActivatedSince(any(), anyBoolean())).thenReturn(0L);
        lenient().when(userRepository.countProTierSince(any(), anyBoolean())).thenReturn(0L);
    }
}
