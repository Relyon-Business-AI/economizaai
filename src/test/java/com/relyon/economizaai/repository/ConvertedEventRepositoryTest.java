package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class ConvertedEventRepositoryTest {

    private static final OffsetDateTime WINDOW_START = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime WINDOW_END = OffsetDateTime.of(2026, 11, 30, 23, 59, 0, 0, ZoneOffset.UTC);

    @Autowired private NotificationEventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;

    private User buyer;
    private User otherHouseholdBuyer;

    @BeforeEach
    void setUp() {
        buyer = createUser("CONV1");
        otherHouseholdBuyer = createUser("CONV2");
    }

    private User createUser(String suffix) {
        var household = householdRepository.save(Household.builder().inviteCode(suffix).build());
        return userRepository.save(User.builder()
                .name("Buyer " + suffix).email("converted-" + suffix.toLowerCase() + "@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private NotificationEvent event(User user, NotificationEventType type, String savings, UUID productId,
                                    OffsetDateTime occurredAt) {
        return eventRepository.save(NotificationEvent.builder()
                .user(user).eventType(type).productId(productId).marketCnpj("12345678000199")
                .savingsAmount(savings == null ? null : new BigDecimal(savings))
                .occurredAt(occurredAt)
                .build());
    }

    @Test
    void findsOnlyThatHouseholdsConvertedEventsInsideTheWindow() {
        var inWindow = event(buyer, NotificationEventType.CONVERTED, "7.20", UUID.randomUUID(), WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.CONVERTED, "3.00", UUID.randomUUID(), WINDOW_START.minusDays(1));
        event(buyer, NotificationEventType.CONVERTED, "3.00", UUID.randomUUID(), WINDOW_END.plusDays(1));
        event(buyer, NotificationEventType.DEAL_VIEWED, null, UUID.randomUUID(), WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.CONVERTED, "2.00", null, WINDOW_START.plusDays(5));
        event(otherHouseholdBuyer, NotificationEventType.CONVERTED, "9.00", UUID.randomUUID(), WINDOW_START.plusDays(5));

        var found = eventRepository.findConvertedBetween(List.of(buyer.getId()), WINDOW_START, WINDOW_END);

        assertEquals(List.of(inWindow.getId()), found.stream().map(NotificationEvent::getId).toList());
    }

    @Test
    void findsThatHouseholdsConvertedEventsOfOneProductAtOneMarketSinceTheCutoff() {
        var productId = UUID.randomUUID();
        var recent = event(buyer, NotificationEventType.CONVERTED, "7.20", productId, WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.CONVERTED, "3.00", productId, WINDOW_START.minusDays(1));
        event(buyer, NotificationEventType.CONVERTED, "3.00", UUID.randomUUID(), WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.DEAL_VIEWED, null, productId, WINDOW_START.plusDays(5));
        event(otherHouseholdBuyer, NotificationEventType.CONVERTED, "9.00", productId, WINDOW_START.plusDays(5));
        var atAnotherMarket = event(buyer, NotificationEventType.CONVERTED, "4.00", productId, WINDOW_START.plusDays(6));
        atAnotherMarket.setMarketCnpj("98765432000188");
        eventRepository.save(atAnotherMarket);

        var found = eventRepository.findConvertedForProductAtMarket(
                List.of(buyer.getId()), productId, "12345678000199", WINDOW_START);

        assertEquals(List.of(recent.getId()), found.stream().map(NotificationEvent::getId).toList());
    }

    @Test
    void findsThatHouseholdsConvertedEventsOfSomeProductsAtOneMarketWithNoTimeBound() {
        var firstProductId = UUID.randomUUID();
        var secondProductId = UUID.randomUUID();
        var recent = event(buyer, NotificationEventType.CONVERTED, "7.20", firstProductId, WINDOW_START.plusDays(5));
        var old = event(buyer, NotificationEventType.CONVERTED, "3.00", secondProductId, WINDOW_START.minusYears(2));
        event(buyer, NotificationEventType.CONVERTED, "3.00", UUID.randomUUID(), WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.DEAL_VIEWED, null, firstProductId, WINDOW_START.plusDays(5));
        event(otherHouseholdBuyer, NotificationEventType.CONVERTED, "9.00", firstProductId, WINDOW_START.plusDays(5));
        var atAnotherMarket = event(buyer, NotificationEventType.CONVERTED, "4.00", firstProductId, WINDOW_START.plusDays(6));
        atAnotherMarket.setMarketCnpj("98765432000188");
        eventRepository.save(atAnotherMarket);

        var found = eventRepository.findConvertedForProductsAtMarket(
                List.of(buyer.getId()), List.of(firstProductId, secondProductId), "12345678000199");

        assertEquals(Set.of(recent.getId(), old.getId()),
                found.stream().map(NotificationEvent::getId).collect(Collectors.toSet()));
    }

    @Test
    void findsTheSentEventsOfOneNotificationForItsRecipientOnly() {
        var notificationId = UUID.randomUUID();
        var announced = sentBy(notificationId, buyer, NotificationEventType.SENT, UUID.randomUUID());
        sentBy(UUID.randomUUID(), buyer, NotificationEventType.SENT, UUID.randomUUID());
        sentBy(notificationId, buyer, NotificationEventType.PUSH_OPENED, UUID.randomUUID());
        sentBy(notificationId, buyer, NotificationEventType.SENT, null);
        sentBy(notificationId, otherHouseholdBuyer, NotificationEventType.SENT, UUID.randomUUID());

        var found = eventRepository.findSentByNotification(buyer.getId(), notificationId);

        assertEquals(List.of(announced.getId()), found.stream().map(NotificationEvent::getId).toList());
    }

    private NotificationEvent sentBy(UUID notificationId, User user, NotificationEventType type, UUID productId) {
        var sent = event(user, type, null, productId, WINDOW_START.plusDays(5));
        sent.setNotificationId(notificationId);
        return eventRepository.save(sent);
    }

    @Test
    void withoutAStart_goesBackToTheFirstConvertedEvent() {
        event(buyer, NotificationEventType.CONVERTED, "7.20", UUID.randomUUID(), WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.CONVERTED, "3.00", UUID.randomUUID(), WINDOW_START.minusYears(1));

        var found = eventRepository.findConvertedBetween(List.of(buyer.getId()), null, WINDOW_END);

        assertEquals(2, found.size());
    }
}
