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
import java.util.UUID;

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
    void withoutAStart_goesBackToTheFirstConvertedEvent() {
        event(buyer, NotificationEventType.CONVERTED, "7.20", UUID.randomUUID(), WINDOW_START.plusDays(5));
        event(buyer, NotificationEventType.CONVERTED, "3.00", UUID.randomUUID(), WINDOW_START.minusYears(1));

        var found = eventRepository.findConvertedBetween(List.of(buyer.getId()), null, WINDOW_END);

        assertEquals(2, found.size());
    }
}
