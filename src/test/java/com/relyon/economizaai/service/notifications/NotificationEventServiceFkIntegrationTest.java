package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reproduces the production FK violation on {@code notification_events_product_id_fkey}:
 * a client-reported telemetry event carrying a {@code productId} that is not a
 * canonical product makes the insert fail. The {@code @DataJpaTest} schema is
 * Hibernate-generated and maps {@code product_id} as a loose UUID column with no
 * FK, so the constraint is recreated here exactly as the V42 migration declares
 * it ({@code REFERENCES products(id) ON DELETE SET NULL}) to exercise the real DB behavior.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(NotificationEventService.class)
class NotificationEventServiceFkIntegrationTest {

    @Autowired private NotificationEventService service;
    @Autowired private NotificationEventRepository eventRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        // Recreate the production FK the migration declares but Hibernate's
        // generated schema omits (product_id is mapped as a plain UUID column).
        entityManager.createNativeQuery(
                "ALTER TABLE notification_events ADD CONSTRAINT notification_events_product_id_fkey "
                        + "FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL")
                .executeUpdate();

        var household = householdRepository.save(Household.builder().inviteCode("INVFK").build());
        user = userRepository.save(User.builder()
                .name("Probe").email("probe-evt@e2e.test").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    @Test
    void record_withUnknownProductId_doesNotViolateFkAndStoresNullProduct() {
        var unknownProductId = UUID.randomUUID(); // never inserted into products

        var saved = service.record(user, NotificationEventType.DEAL_TAPPED,
                RecordContext.builder().productId(unknownProductId).channel("PUSH").build());
        eventRepository.flush();

        var loaded = eventRepository.findById(saved.getId()).orElseThrow();
        assertEquals(NotificationEventType.DEAL_TAPPED, loaded.getEventType());
        assertNull(loaded.getProductId(), "unknown product reference must be dropped, not persisted as a hard FK");
    }
}
