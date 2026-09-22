package com.relyon.economizai.service.admin;

import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.repository.PriceObservationAuditRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOverviewServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private PriceObservationAuditRepository priceObservationAuditRepository;
    @InjectMocks private AdminOverviewService service;

    @Test
    void aggregatesCrossAreaKpisAndParseRate() {
        when(userRepository.countUsers(false)).thenReturn(72L);
        when(userRepository.countSignupsSince(any(), anyBoolean())).thenReturn(3L);
        when(userRepository.countProTier(false)).thenReturn(54L);
        when(subscriptionRepository.countPaying(eq(SubscriptionStatus.ACTIVE), anyBoolean())).thenReturn(0L);
        when(receiptRepository.count()).thenReturn(326L);
        when(receiptRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(12L);
        when(receiptRepository.countActiveHouseholdsSince(any())).thenReturn(9L);
        when(receiptRepository.sumConfirmedTotal()).thenReturn(new BigDecimal("15000.00"));
        when(receiptRepository.statusBreakdownSince(any())).thenReturn(List.<Object[]>of(
                new Object[]{ReceiptStatus.CONFIRMED, 80L},
                new Object[]{ReceiptStatus.FAILED_PARSE, 20L}));
        when(priceObservationRepository.count()).thenReturn(5000L);
        when(priceObservationAuditRepository.countDistinctContributingHouseholds()).thenReturn(40L);

        var overview = service.overview();

        assertEquals(72L, overview.usersTotal());
        assertEquals(54L, overview.usersPro());
        assertEquals(326L, overview.receiptsTotal());
        assertEquals(12L, overview.receiptsToday());
        assertEquals(0.8d, overview.parseRate30d(), 0.001); // 80 / (80 + 20)
        assertEquals(9L, overview.activeHouseholds7d());
        assertEquals(0, new BigDecimal("15000.00").compareTo(overview.totalSpendConfirmed()));
        assertEquals(5000L, overview.priceObservations());
        assertEquals(40L, overview.contributingHouseholds());
    }
}
