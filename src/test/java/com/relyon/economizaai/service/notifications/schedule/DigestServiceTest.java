package com.relyon.economizaai.service.notifications.schedule;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.NotificationSystemSetting;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.NotificationSystemSettingRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private NotificationService notificationService;
    @Mock private NotificationSystemSettingRepository settingsRepository;

    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    private DigestService service;

    @BeforeEach
    void setUp() {
        // Settings reais (sem override) sobre repo mockado — templates caem no i18n default.
        lenient().when(settingsRepository.findByJob(any())).thenAnswer(invocation ->
                Optional.of(NotificationSystemSetting.builder()
                        .job(invocation.getArgument(0)).enabled(true).build()));
        var settingsService = new NotificationSettingsService(settingsRepository, realMessageService());
        service = new DigestService(ruleRepository, receiptRepository, notificationService, settingsService);
    }

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
    }

    private NotificationRule digestRule() {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user()).type(NotificationType.DIGEST).isDefault(true).active(true)
                .build();
    }

    @Test
    void run_sendsDigestWhenHouseholdHadActivity() {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any())).thenReturn(3L);
        when(receiptRepository.sumConfirmedTotalSince(eq(HOUSEHOLD_ID), any()))
                .thenReturn(new BigDecimal("210.50"));

        service.run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.DIGEST, captor.getValue().type());
    }

    @Test
    void run_readsActivityBeforeDispatching() {
        // The window reads (which decide whether to notify) run before the network send;
        // run() holds no transaction across notify() (tx boundary moved off the loop).
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any())).thenReturn(3L);
        when(receiptRepository.sumConfirmedTotalSince(eq(HOUSEHOLD_ID), any()))
                .thenReturn(new BigDecimal("210.50"));

        service.run();

        var inOrder = inOrder(receiptRepository, notificationService);
        inOrder.verify(receiptRepository).countByHouseholdIdAndStatusAndConfirmedAtAfter(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any());
        inOrder.verify(receiptRepository).sumConfirmedTotalSince(eq(HOUSEHOLD_ID), any());
        inOrder.verify(notificationService).notify(any(NotificationPayload.class));
    }

    @Test
    void runMonthly_sendsMonthNamedDigestForPreviousMonthWindow() {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtGreaterThanEqualAndConfirmedAtBefore(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any(), any())).thenReturn(12L);
        when(receiptRepository.sumConfirmedTotalBetween(eq(HOUSEHOLD_ID), any(), any()))
                .thenReturn(new BigDecimal("843.20"));

        service.runMonthly();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals(NotificationType.DIGEST, payload.type());
        assertTrue(payload.title().startsWith("Seu resumo de "), "título carrega o nome do mês");
        assertTrue(payload.body().contains("12"), "corpo carrega o total de notas do mês");
        // janela = mês civil anterior: [1º do mês passado, 1º deste mês)
        var fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(receiptRepository).countByHouseholdIdAndStatusAndConfirmedAtGreaterThanEqualAndConfirmedAtBefore(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), fromCaptor.capture(), toCaptor.capture());
        assertEquals(1, fromCaptor.getValue().getDayOfMonth());
        assertEquals(1, toCaptor.getValue().getDayOfMonth());
        assertEquals(fromCaptor.getValue().toLocalDate().plusMonths(1), toCaptor.getValue().toLocalDate());
    }

    @Test
    void runMonthly_skipsHouseholdWithNoActivityInMonth() {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtGreaterThanEqualAndConfirmedAtBefore(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any(), any())).thenReturn(0L);

        service.runMonthly();

        verify(notificationService, never()).notify(any());
        verify(receiptRepository, never()).sumConfirmedTotalBetween(any(), any(), any());
    }

    @Test
    void run_skipsHouseholdWithNoActivity() {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any())).thenReturn(0L);

        service.run();

        verify(notificationService, never()).notify(any());
        verify(receiptRepository, never()).sumConfirmedTotalSince(any(), any());
    }
}
