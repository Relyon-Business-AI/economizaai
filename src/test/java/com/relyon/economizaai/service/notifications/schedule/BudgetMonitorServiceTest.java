package com.relyon.economizaai.service.notifications.schedule;
import org.mockito.Spy;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.time.BrazilClock;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetMonitorServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private InsightsRepository insightsRepository;
    @Mock private NotificationService notificationService;
    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    @Spy private LocalizedMessageService messageService = realMessageService();
    @Mock private NotificationSettingsService settingsService;
    @Mock private UserRepository userRepository;
    private final List<User> householdMembers = new ArrayList<>();
    @InjectMocks private BudgetMonitorService service;

    @BeforeEach
    void enableJobSetting() {
        lenient().when(settingsService.isEnabled(NotificationJob.BUDGET_ALERT)).thenReturn(true);
        lenient().when(userRepository.findAllByHouseholdId(HOUSEHOLD_ID))
                .thenAnswer(invocation -> List.copyOf(householdMembers));
    }

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();

    /** A member of the household (registered as one of its members). */
    private User user() {
        var member = User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
        householdMembers.add(member);
        return member;
    }

    private NotificationRule budgetRule(BigDecimal threshold, LocalDateTime lastFired) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user()).type(NotificationType.BUDGET)
                .thresholdPrice(threshold).active(true).lastFiredAt(lastFired)
                .build();
    }

    @Test
    void run_firesWhenSpendReachesThreshold() {
        var rule = budgetRule(new BigDecimal("500.00"), null);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(rule));
        when(insightsRepository.totalSpendAllSegments(eq(HOUSEHOLD_ID), any(), any()))
                .thenReturn(new BigDecimal("620.00"));

        service.run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.BUDGET, captor.getValue().type());
        verify(ruleRepository).save(rule);
    }

    @Test
    void run_usesThePurchaseDateWindowOfTheCurrentMonthAndBrazilianMoney() {
        var rule = budgetRule(new BigDecimal("1500.00"), null);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(rule));
        var fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(insightsRepository.totalSpendAllSegments(eq(HOUSEHOLD_ID), fromCaptor.capture(), toCaptor.capture()))
                .thenReturn(new BigDecimal("1620.5"));

        service.run();

        var month = BrazilClock.currentYearMonth();
        assertEquals(month.atDay(1).atStartOfDay(), fromCaptor.getValue());
        assertEquals(month, YearMonth.from(toCaptor.getValue()));
        assertEquals(month.plusMonths(1), YearMonth.from(toCaptor.getValue().plusNanos(1)));
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertTrue(captor.getValue().body().contains("R$ 1.620,50"), captor.getValue().body());
        assertTrue(captor.getValue().body().contains("R$ 1.500,00"), captor.getValue().body());
    }

    @Test
    void run_persistsFiredTimestampOnlyAfterSend() {
        // Guards that the notify() dispatch is NOT wrapped in the persist transaction:
        // the send happens first, the fired-timestamp is saved afterwards.
        var rule = budgetRule(new BigDecimal("500.00"), null);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(rule));
        when(insightsRepository.totalSpendAllSegments(eq(HOUSEHOLD_ID), any(), any()))
                .thenReturn(new BigDecimal("620.00"));

        service.run();

        var inOrder = inOrder(notificationService, ruleRepository);
        inOrder.verify(notificationService).notify(any(NotificationPayload.class));
        inOrder.verify(ruleRepository).save(rule);
    }

    @Test
    void run_doesNotFireWhenSpendBelowThreshold() {
        var rule = budgetRule(new BigDecimal("500.00"), null);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(rule));
        when(insightsRepository.totalSpendAllSegments(eq(HOUSEHOLD_ID), any(), any()))
                .thenReturn(new BigDecimal("120.00"));

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_skipsWhenAlreadyFiredThisMonth() {
        var rule = budgetRule(new BigDecimal("500.00"), LocalDateTime.now());
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(rule));

        service.run();

        verify(notificationService, never()).notify(any());
        verify(insightsRepository, never()).totalSpendAllSegments(any(), any(), any());
    }

    @Test
    void run_notifiesEveryMemberOfTheHousehold() {
        var rule = budgetRule(new BigDecimal("500.00"), null);
        var otherMember = user();
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(rule));
        when(insightsRepository.totalSpendAllSegments(eq(HOUSEHOLD_ID), any(), any()))
                .thenReturn(new BigDecimal("620.00"));

        service.run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService, times(2)).notify(captor.capture());
        var recipients = captor.getAllValues().stream().map(NotificationPayload::user).toList();
        assertEquals(List.of(rule.getUser(), otherMember), recipients);
    }

    @Test
    void run_theMostRecentlyChangedLimitIsTheOneInForceForTheHousehold() {
        var olderLimit = budgetRule(new BigDecimal("300.00"), null);
        olderLimit.setUpdatedAt(LocalDateTime.now().minusDays(10));
        var newerLimit = budgetRule(new BigDecimal("900.00"), null);
        newerLimit.setUpdatedAt(LocalDateTime.now().minusDays(1));
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(olderLimit, newerLimit));
        when(insightsRepository.totalSpendAllSegments(eq(HOUSEHOLD_ID), any(), any()))
                .thenReturn(new BigDecimal("620.00"));

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_aHouseholdAlreadyNotifiedThisMonthIsNotNotifiedAgainByAnotherMembersLimit() {
        var firedLimit = budgetRule(new BigDecimal("500.00"), LocalDateTime.now());
        var otherMembersLimit = budgetRule(new BigDecimal("400.00"), null);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET))
                .thenReturn(List.of(firedLimit, otherMembersLimit));

        service.run();

        verify(notificationService, never()).notify(any());
    }
}
