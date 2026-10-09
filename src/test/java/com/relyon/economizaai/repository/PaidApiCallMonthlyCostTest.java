package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PaidApiCall;
import com.relyon.economizaai.model.enums.PaidApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the per-user monthly cost breakdown groups by calendar month in Brazil
 * time (not UTC). Exercises {@code to_char(... AT TIME ZONE 'America/Sao_Paulo')}
 * on H2 in PostgreSQL mode (the @DataJpaTest backend) and the interface projection
 * mapping of the native query.
 */
@DataJpaTest
@ActiveProfiles("test")
class PaidApiCallMonthlyCostTest {

    @Autowired private PaidApiCallRepository paidApiCallRepository;

    @Test
    void groupsSpendByBrazilMonthMostRecentFirst() {
        var userId = UUID.randomUUID();

        // Two calls in October (BRT), total 27 cents.
        save(userId, PaidApiService.INFOSIMPLES, 24, OffsetDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC));
        save(userId, PaidApiService.CAPTCHA_SOLVE, 3, OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC));
        // One call stored 2026-10-01 02:00 UTC == 2026-09-30 23:00 BRT -> belongs to September.
        save(userId, PaidApiService.CAPTCHA_SOLVE, 3, OffsetDateTime.of(2026, 10, 1, 2, 0, 0, 0, ZoneOffset.UTC));
        // Another user's call must not leak in.
        save(UUID.randomUUID(), PaidApiService.INFOSIMPLES, 24, OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneOffset.UTC));

        var months = paidApiCallRepository.costByMonthForUser(userId);

        assertEquals(2, months.size());
        // Most recent first.
        assertEquals("2026-10", months.get(0).getYearMonth());
        assertEquals(2, months.get(0).getCalls());
        assertEquals(27, months.get(0).getCostCents());
        assertEquals("2026-09", months.get(1).getYearMonth());
        assertEquals(1, months.get(1).getCalls());
        assertEquals(3, months.get(1).getCostCents());
    }

    private void save(UUID userId, PaidApiService service, int costCents, OffsetDateTime at) {
        paidApiCallRepository.save(PaidApiCall.builder()
                .userId(userId)
                .service(service)
                .success(true)
                .estimatedCostCents(costCents)
                .createdAt(at)
                .build());
    }
}
