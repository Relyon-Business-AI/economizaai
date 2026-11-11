package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.ConsumptionSnooze;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.ManualPurchase;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import com.relyon.economizaai.service.notifications.NotificationTextFormatter;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the reminder job over the REAL forecast ({@link ConsumptionIntelligenceService})
 * with mocked repositories, so "the push follows the forecast screen" is what is tested.
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private NotificationService notificationService;
    @Mock private NotificationSettingsService settingsService;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ConsumptionSnoozeRepository snoozeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private CategoryRepository categoryRepository;

    private ReplenishmentService service;
    private User user;
    private Product coffee;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        properties.getConsumption().setMinPurchasesForPrediction(3);
        properties.getConsumption().setFallbackMinPurchasesForPrediction(2);
        var consumptionService = new ConsumptionIntelligenceService(receiptItemRepository, manualPurchaseRepository,
                snoozeRepository, productRepository, properties, householdProductAliasService, categoryRepository);
        var messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
        service = new ReplenishmentService(ruleRepository, consumptionService, notificationService,
                new LocalizedMessageService(messageSource), settingsService);

        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("replenish@economizaai.app").household(household).build();
        coffee = Product.builder().id(UUID.randomUUID()).normalizedName("Café").build();
        now = BrazilClock.nowDateTime();

        lenient().when(settingsService.isEnabled(NotificationJob.REPLENISHMENT)).thenReturn(true);
        lenient().when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(any(), any())).thenReturn(List.of());
    }

    private NotificationRule stockoutRule(Product product) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user).type(NotificationType.STOCKOUT).product(product)
                .leadTimeDays(3).active(true).build();
    }

    private void activeRules(NotificationRule... rules) {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.STOCKOUT))
                .thenReturn(List.of(rules));
    }

    /** Scanned purchases of the product, each this many days ago. */
    private void scannedPurchases(Product product, int... daysAgo) {
        var items = new ArrayList<ReceiptItem>();
        for (var days : daysAgo) {
            var receipt = Receipt.builder().id(UUID.randomUUID()).household(user.getHousehold()).user(user)
                    .issuedAt(now.minusDays(days)).build();
            items.add(ReceiptItem.builder().id(UUID.randomUUID()).receipt(receipt).product(product)
                    .lineNumber(1).rawDescription(product.getNormalizedName()).quantity(BigDecimal.ONE).build());
        }
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);
    }

    @Test
    void run_remindsWhenTheForecastSaysTheProductRanOut() {
        var rule = stockoutRule(coffee);
        activeRules(rule);
        scannedPurchases(coffee, 40, 30, 20); // every 10 days, last one 20 days ago → ran out

        service.run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.STOCKOUT, captor.getValue().type());
        var lastPurchase = NotificationTextFormatter.dayMonth(now.minusDays(20).toLocalDate());
        assertTrue(captor.getValue().body().contains("a cada ~10 dias"), captor.getValue().body());
        assertTrue(captor.getValue().body().contains("a última foi em " + lastPurchase), captor.getValue().body());
        assertEquals(1, rule.getReminderCount());
        assertEquals(now.minusDays(20).toLocalDate(), rule.getReminderPurchaseDate());
    }

    @Test
    void run_persistsTheReminderOnlyAfterTheSend() {
        var rule = stockoutRule(coffee);
        activeRules(rule);
        scannedPurchases(coffee, 40, 30, 20);

        service.run();

        var order = inOrder(notificationService, ruleRepository);
        order.verify(notificationService).notify(any(NotificationPayload.class));
        order.verify(ruleRepository).save(rule);
    }

    @Test
    void run_staysQuietWhileTheForecastSaysOk() {
        activeRules(stockoutRule(coffee));
        scannedPurchases(coffee, 62, 32, 2); // every 30 days, bought 2 days ago

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_staysQuietWithoutEnoughHistoryToForecast() {
        activeRules(stockoutRule(coffee));
        scannedPurchases(coffee, 20);

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_respectsAdiar_aSnoozedProductIsNotReminded() {
        activeRules(stockoutRule(coffee));
        scannedPurchases(coffee, 40, 30, 20);
        when(snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(any(), any())).thenReturn(List.of(
                ConsumptionSnooze.builder().household(user.getHousehold()).product(coffee)
                        .snoozedUntil(now.plusDays(5)).build()));

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_respectsJaComprei_aManualPurchaseMovesTheForecastForward() {
        activeRules(stockoutRule(coffee));
        scannedPurchases(coffee, 62, 32, 31); // overdue by the scans alone
        when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of(
                ManualPurchase.builder().household(user.getHousehold()).user(user).product(coffee)
                        .quantity(BigDecimal.ONE).purchasedAt(now.minusDays(1)).build()));

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_stopsAfterThreeRemindersForTheSamePurchase() {
        var rule = stockoutRule(coffee);
        rule.setReminderCount(ReplenishmentService.MAX_REMINDERS_PER_PURCHASE);
        rule.setReminderPurchaseDate(now.minusDays(20).toLocalDate());
        rule.setLastFiredAt(LocalDateTime.now().minusDays(30));
        activeRules(rule);
        scannedPurchases(coffee, 40, 30, 20);

        service.run();

        verify(notificationService, never()).notify(any());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void run_thirdReminderStillGoesOut() {
        var rule = stockoutRule(coffee);
        rule.setReminderCount(2);
        rule.setReminderPurchaseDate(now.minusDays(20).toLocalDate());
        rule.setLastFiredAt(LocalDateTime.now().minusDays(8));
        activeRules(rule);
        scannedPurchases(coffee, 40, 30, 20);

        service.run();

        verify(notificationService).notify(any());
        assertEquals(3, rule.getReminderCount());
    }

    @Test
    void run_aNewPurchaseStartsTheCountAgain() {
        var rule = stockoutRule(coffee);
        rule.setReminderCount(ReplenishmentService.MAX_REMINDERS_PER_PURCHASE);
        rule.setReminderPurchaseDate(now.minusDays(60).toLocalDate()); // the purchase the old reminders were about
        rule.setLastFiredAt(LocalDateTime.now().minusDays(30));
        activeRules(rule);
        scannedPurchases(coffee, 60, 40, 20); // bought again 40 and 20 days ago, due once more

        service.run();

        verify(notificationService).notify(any());
        assertEquals(1, rule.getReminderCount());
        assertEquals(now.minusDays(20).toLocalDate(), rule.getReminderPurchaseDate());
    }

    @Test
    void run_spacesRemindersByTheCooldown() {
        var rule = stockoutRule(coffee);
        rule.setReminderCount(1);
        rule.setReminderPurchaseDate(now.minusDays(20).toLocalDate());
        rule.setLastFiredAt(LocalDateTime.now().minusDays(2));
        activeRules(rule);
        scannedPurchases(coffee, 40, 30, 20);

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_oneForecastPerUserEvenWithSeveralRules() {
        var milk = Product.builder().id(UUID.randomUUID()).normalizedName("Leite").build();
        activeRules(stockoutRule(coffee), stockoutRule(milk));
        scannedPurchases(coffee, 40, 30, 20);

        service.run();

        verify(receiptItemRepository, times(1)).findConfirmedHistoryForHousehold(any());
        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void run_oneUsersFailureDoesNotStopTheOthers() {
        var otherUser = User.builder().id(UUID.randomUUID()).email("replenish2@economizaai.app")
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("XYZ789").build()).build();
        var failingRule = NotificationRule.builder().id(UUID.randomUUID())
                .user(otherUser).type(NotificationType.STOCKOUT).product(coffee).active(true).build();
        var healthyRule = stockoutRule(coffee);
        activeRules(failingRule, healthyRule);
        scannedPurchases(coffee, 40, 30, 20);
        doThrow(new IllegalStateException("expo down")).doReturn(null).when(notificationService).notify(any());

        service.run();

        verify(notificationService, times(2)).notify(any());
        assertEquals(1, healthyRule.getReminderCount());
        assertEquals(0, failingRule.getReminderCount());
    }

    @Test
    void run_doesNothingWhenTheJobIsDisabled() {
        when(settingsService.isEnabled(NotificationJob.REPLENISHMENT)).thenReturn(false);

        service.run();

        verify(ruleRepository, never()).findActiveByTypeFetchUserAndProduct(any());
    }
}
