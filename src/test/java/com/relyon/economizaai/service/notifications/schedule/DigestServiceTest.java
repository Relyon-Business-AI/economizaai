package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.NotificationSystemSetting;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.NotificationSystemSettingRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private InsightsRepository insightsRepository;
    @Mock private NotificationService notificationService;
    @Mock private NotificationSystemSettingRepository settingsRepository;

    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    private final List<Runnable> queuedRuns = new ArrayList<>();
    private NotificationSettingsService settingsService;

    @BeforeEach
    void setUp() {
        // Settings reais (sem override) sobre repo mockado — templates caem no i18n default.
        lenient().when(settingsRepository.findByJob(any())).thenAnswer(invocation ->
                Optional.of(NotificationSystemSetting.builder()
                        .job(invocation.getArgument(0)).enabled(true).build()));
        settingsService = new NotificationSettingsService(settingsRepository, realMessageService());
    }

    private DigestService service(Executor executor) {
        return new DigestService(ruleRepository, insightsRepository, notificationService, settingsService, executor);
    }

    private DigestService inlineService() {
        return service(Runnable::run);
    }

    private NotificationRule digestRuleFor(UUID householdId) {
        var user = User.builder().id(UUID.randomUUID()).email("digest@economizaai.app")
                .household(Household.builder().id(householdId).build())
                .build();
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user).type(NotificationType.DIGEST).isDefault(true).active(true)
                .build();
    }

    /** One page with the given rules, then an empty page. */
    private void activeDigestRules(NotificationRule... rules) {
        when(ruleRepository.findActivePageByTypeFetchUser(eq(NotificationType.DIGEST), any(), any()))
                .thenReturn(List.of(rules)).thenReturn(List.of());
    }

    private static Object[] spendRow(UUID householdId, String total, long receiptCount) {
        return new Object[]{householdId, new BigDecimal(total), receiptCount};
    }

    @Test
    void run_sendsTheWeeklyDigestWithBrazilianMoneyToHouseholdsWithPurchases() {
        var householdId = UUID.randomUUID();
        activeDigestRules(digestRuleFor(householdId));
        when(insightsRepository.spendByHouseholds(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(spendRow(householdId, "1210.5", 3)));

        inlineService().run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.DIGEST, captor.getValue().type());
        assertTrue(captor.getValue().body().contains("3 nota(s)"), captor.getValue().body());
        assertTrue(captor.getValue().body().contains("R$ 1.210,50"), captor.getValue().body());
    }

    @Test
    void run_windowIsTheLastSevenDaysOfPurchases() {
        var householdId = UUID.randomUUID();
        activeDigestRules(digestRuleFor(householdId));
        var fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(insightsRepository.spendByHouseholds(anyCollection(), fromCaptor.capture(), toCaptor.capture()))
                .thenReturn(List.of());

        inlineService().run();

        assertEquals(7, ChronoUnit.DAYS.between(fromCaptor.getValue(), toCaptor.getValue()));
    }

    @Test
    void run_skipsHouseholdsWithoutPurchasesInTheWindow() {
        var active = UUID.randomUUID();
        var silent = UUID.randomUUID();
        var activeRule = digestRuleFor(active);
        activeDigestRules(activeRule, digestRuleFor(silent));
        when(insightsRepository.spendByHouseholds(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(spendRow(active, "80.00", 1)));

        inlineService().run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(activeRule.getUser(), captor.getValue().user());
    }

    @Test
    void run_asksForSpendOncePerPageNotOncePerUser() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        activeDigestRules(digestRuleFor(first), digestRuleFor(second), digestRuleFor(first));
        var idsCaptor = ArgumentCaptor.forClass(Collection.class);
        when(insightsRepository.spendByHouseholds(idsCaptor.capture(), any(), any()))
                .thenReturn(List.<Object[]>of(spendRow(first, "10.00", 1), spendRow(second, "20.00", 2)));

        inlineService().run();

        verify(insightsRepository, times(1)).spendByHouseholds(anyCollection(), any(), any());
        assertEquals(2, idsCaptor.getValue().size(), "household ids are de-duplicated");
        verify(notificationService, times(3)).notify(any());
    }

    @Test
    void run_walksEveryPageUsingTheLastIdAsCursor() {
        var householdId = UUID.randomUUID();
        var firstPageRule = digestRuleFor(householdId);
        var secondPageRule = digestRuleFor(householdId);
        when(ruleRepository.findActivePageByTypeFetchUser(eq(NotificationType.DIGEST), any(), any()))
                .thenReturn(List.of(firstPageRule))
                .thenReturn(List.of(secondPageRule))
                .thenReturn(List.of());
        when(insightsRepository.spendByHouseholds(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(spendRow(householdId, "10.00", 1)));

        inlineService().run();

        verify(ruleRepository).findActivePageByTypeFetchUser(eq(NotificationType.DIGEST),
                eq(firstPageRule.getId()), any());
        verify(ruleRepository).findActivePageByTypeFetchUser(eq(NotificationType.DIGEST),
                eq(secondPageRule.getId()), any());
        verify(notificationService, times(2)).notify(any());
    }

    @Test
    void run_oneRecipientFailingDoesNotStopTheOthers() {
        var householdId = UUID.randomUUID();
        var failing = digestRuleFor(householdId);
        var healthy = digestRuleFor(householdId);
        activeDigestRules(failing, healthy);
        when(insightsRepository.spendByHouseholds(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(spendRow(householdId, "10.00", 1)));
        doThrow(new IllegalStateException("expo down")).doReturn(null).when(notificationService).notify(any());

        inlineService().run();

        verify(notificationService, times(2)).notify(any());
    }

    @Test
    void runMonthly_sendsMonthNamedDigestForPreviousCalendarMonthOfPurchases() {
        var householdId = UUID.randomUUID();
        activeDigestRules(digestRuleFor(householdId));
        var fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(insightsRepository.spendByHouseholds(anyCollection(), fromCaptor.capture(), toCaptor.capture()))
                .thenReturn(List.<Object[]>of(spendRow(householdId, "843.20", 12)));

        inlineService().runMonthly();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertTrue(payload.title().startsWith("Seu resumo de "), "título carrega o nome do mês");
        assertTrue(payload.body().contains("12 nota(s)"), payload.body());
        assertTrue(payload.body().contains("R$ 843,20"), payload.body());
        // janela = mês civil anterior inteiro, pela data da compra
        assertEquals(1, fromCaptor.getValue().getDayOfMonth());
        assertEquals(fromCaptor.getValue().getMonth(), toCaptor.getValue().getMonth());
        assertEquals(fromCaptor.getValue().plusMonths(1), toCaptor.getValue().plusNanos(1));
    }

    @Test
    void runMonthly_skipsHouseholdWithNoPurchasesInTheMonth() {
        activeDigestRules(digestRuleFor(UUID.randomUUID()));
        when(insightsRepository.spendByHouseholds(anyCollection(), any(), any())).thenReturn(List.of());

        inlineService().runMonthly();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void tick_claimsFirstThenRunsOffTheSchedulerThread() {
        when(settingsRepository.claimRun(eq(NotificationJob.WEEKLY_DIGEST), any())).thenReturn(1);
        when(settingsRepository.claimRun(eq(NotificationJob.MONTHLY_DIGEST), any())).thenReturn(0);
        var digestService = service(queuedRuns::add);

        digestService.tick();

        // Claimed, handed off — nothing was read or sent on the scheduler thread.
        assertEquals(1, queuedRuns.size());
        verify(ruleRepository, never()).findActivePageByTypeFetchUser(any(), any(), any());

        when(ruleRepository.findActivePageByTypeFetchUser(eq(NotificationType.DIGEST), any(), any()))
                .thenReturn(List.of());
        queuedRuns.get(0).run();
        verify(ruleRepository).findActivePageByTypeFetchUser(eq(NotificationType.DIGEST), any(), any());
    }

    @Test
    void tick_aLostClaimSendsNothing() {
        when(settingsRepository.claimRun(any(), any())).thenReturn(0);
        var digestService = service(queuedRuns::add);

        digestService.tick();

        assertTrue(queuedRuns.isEmpty());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void tick_fullPoolFallsBackToRunningInlineSoTheClaimedDayIsNotLost() {
        when(settingsRepository.claimRun(eq(NotificationJob.WEEKLY_DIGEST), any())).thenReturn(1);
        when(settingsRepository.claimRun(eq(NotificationJob.MONTHLY_DIGEST), any())).thenReturn(0);
        when(ruleRepository.findActivePageByTypeFetchUser(eq(NotificationType.DIGEST), any(), any()))
                .thenReturn(List.of());
        var digestService = service(task -> {
            throw new RejectedExecutionException("queue full");
        });

        digestService.tick();

        verify(ruleRepository).findActivePageByTypeFetchUser(eq(NotificationType.DIGEST), any(), any());
    }
}
