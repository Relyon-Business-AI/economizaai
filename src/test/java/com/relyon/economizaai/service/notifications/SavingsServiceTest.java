package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationEventRepository.SavingsRollup;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsServiceTest {

    @Mock private NotificationEventRepository eventRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private SavingsService service;

    private User user;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).name("John").email("john@test.com").household(household).build();
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));
    }

    @Test
    void sumsLifetimeAndLast30DaysForHousehold() {
        when(eventRepository.sumConvertedSavings(anyList(), isNull()))
                .thenReturn(rollup(new BigDecimal("42.50"), 7));
        when(eventRepository.sumConvertedSavings(anyList(), any(OffsetDateTime.class)))
                .thenReturn(rollup(new BigDecimal("15.00"), 3));

        var response = service.summarize(user);

        assertEquals(0, response.totalSavings().compareTo(new BigDecimal("42.50")));
        assertEquals(7, response.conversions());
        assertEquals(0, response.last30DaysSavings().compareTo(new BigDecimal("15.00")));
    }

    @Test
    void recentSavingsLookBackTheDaysSetInTheAdmin() {
        when(eventRepository.sumConvertedSavings(anyList(), isNull()))
                .thenReturn(rollup(new BigDecimal("42.50"), 7));
        when(eventRepository.sumConvertedSavings(anyList(), any(OffsetDateTime.class)))
                .thenReturn(rollup(new BigDecimal("15.00"), 3));
        var recentSince = ArgumentCaptor.forClass(OffsetDateTime.class);

        service.summarize(user);
        service.setRecentSavingsDays(7);
        service.summarize(user);

        verify(eventRepository, times(4)).sumConvertedSavings(anyList(), recentSince.capture());
        var cutoffs = recentSince.getAllValues().stream().filter(Objects::nonNull).toList();
        assertEquals(30, ChronoUnit.DAYS.between(cutoffs.get(0), OffsetDateTime.now().plusMinutes(1)));
        assertEquals(7, ChronoUnit.DAYS.between(cutoffs.get(1), OffsetDateTime.now().plusMinutes(1)));
    }

    @Test
    void emptyHouseholdHistoryReturnsZero() {
        when(eventRepository.sumConvertedSavings(anyList(), isNull()))
                .thenReturn(rollup(BigDecimal.ZERO, 0));
        when(eventRepository.sumConvertedSavings(anyList(), any(OffsetDateTime.class)))
                .thenReturn(rollup(BigDecimal.ZERO, 0));

        var response = service.summarize(user);

        assertEquals(0, response.totalSavings().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.conversions());
        assertEquals(0, response.last30DaysSavings().compareTo(BigDecimal.ZERO));
    }

    private SavingsRollup rollup(BigDecimal total, long conversions) {
        return new SavingsRollup() {
            @Override
            public BigDecimal getTotalSavings() {
                return total;
            }

            @Override
            public long getConversions() {
                return conversions;
            }
        };
    }
}
