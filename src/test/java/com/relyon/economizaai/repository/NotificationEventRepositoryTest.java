package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NotificationEventRepositoryTest {

    @Autowired private NotificationEventRepository eventRepository;
    @Autowired private DealSurfaceStateRepository stateRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private EntityManager entityManager;

    private User createUser(String suffix) {
        var household = householdRepository.save(Household.builder().inviteCode("INV" + suffix).build());
        return userRepository.save(User.builder()
                .name("User " + suffix).email("user" + suffix + "@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Product createProduct(String name) {
        return productRepository.save(Product.builder().normalizedName(name).build());
    }

    @Test
    void persistsAndReadsBackEvent() {
        var user = createUser("E1");
        var product = createProduct("ARROZ 5KG");

        var event = eventRepository.save(NotificationEvent.builder()
                .user(user)
                .eventType(NotificationEventType.DEAL_TAPPED)
                .productId(product.getId())
                .marketCnpj("12345678000199")
                .channel("PUSH")
                .metadata("{\"discountFraction\":0.22}")
                .build());

        var loaded = eventRepository.findById(event.getId()).orElseThrow();
        assertEquals(NotificationEventType.DEAL_TAPPED, loaded.getEventType());
        assertEquals(product.getId(), loaded.getProductId());
        assertEquals("12345678000199", loaded.getMarketCnpj());
        assertNotNull(loaded.getOccurredAt(), "occurred_at defaulted on persist");
    }

    @Test
    void findByUserIdAndProductIdAndMarketCnpj_returnsMatchingState() {
        var user = createUser("S1");
        var product = createProduct("LEITE 1L");
        stateRepository.save(DealSurfaceState.builder()
                .userId(user.getId())
                .productId(product.getId())
                .marketCnpj("99999999000188")
                .lastDiscountFraction(new BigDecimal("0.1500"))
                .lastUnitPrice(new BigDecimal("4.99"))
                .build());

        var found = stateRepository.findByUserIdAndProductIdAndMarketCnpj(
                user.getId(), product.getId(), "99999999000188");
        assertTrue(found.isPresent());
        assertEquals(new BigDecimal("0.1500"), found.get().getLastDiscountFraction());

        var miss = stateRepository.findByUserIdAndProductIdAndMarketCnpj(
                user.getId(), product.getId(), "00000000000000");
        assertTrue(miss.isEmpty());
    }

    @Test
    void eventsAreScopedToTheirUserAndRemovableForLgpdDelete() {
        // The migration declares user_id ... ON DELETE CASCADE so a user-account
        // delete reaps the telemetry. The @DataJpaTest schema is Hibernate-generated
        // (Flyway off, ddl-auto=create-drop), so the DB FK cascade isn't exercised
        // here; we verify the rows are user-scoped and reaped when removed for the
        // user, which is the LGPD-relevant behavior.
        var user = createUser("C1");
        eventRepository.save(NotificationEvent.builder()
                .user(user)
                .eventType(NotificationEventType.SCREEN_OPENED)
                .build());
        eventRepository.flush();
        entityManager.clear();

        var userId = user.getId();
        var owned = eventRepository.findAll().stream()
                .filter(event -> userId.equals(event.getUser().getId()))
                .toList();
        assertEquals(1, owned.size());

        eventRepository.deleteAll(owned);
        eventRepository.flush();
        assertEquals(0, eventRepository.count());
    }
}
