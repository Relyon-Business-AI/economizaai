package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proactive backfill ({@link NotificationRuleRepository#insertMissingDefaultRules})
 * must materialize one default rule per default type for every existing user, with
 * {@code is_default} / {@code active} true and a null product — and be idempotent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NotificationRuleBackfillTest {

    @Autowired private NotificationRuleRepository ruleRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private User createUser(String suffix) {
        var household = householdRepository.save(Household.builder().inviteCode("INV" + suffix).build());
        return userRepository.save(User.builder()
                .name("User " + suffix).email("user" + suffix + "@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    @Test
    void insertMissingDefaultRules_materializesOneDefaultPerTypePerUser() {
        createUser("A");
        createUser("B");

        var totalInserted = 0;
        for (var type : NotificationType.defaults()) {
            totalInserted += ruleRepository.insertMissingDefaultRules(type.name());
        }

        // 2 users x N default types.
        var expected = 2 * NotificationType.defaults().size();
        assertEquals(expected, totalInserted);
        assertEquals(expected, ruleRepository.count());

        for (var rule : ruleRepository.findAll()) {
            assertTrue(rule.isDefault(), "backfilled rule must be a default");
            assertTrue(rule.isActive(), "backfilled rule must be active");
            assertNull(rule.getProduct(), "default rule has no product");
            assertTrue(NotificationType.defaults().contains(rule.getType()));
        }
    }

    @Test
    void insertMissingDefaultRules_isIdempotent() {
        createUser("C");

        for (var type : NotificationType.defaults()) {
            ruleRepository.insertMissingDefaultRules(type.name());
        }
        var afterFirst = ruleRepository.count();

        var secondRun = 0;
        for (var type : NotificationType.defaults()) {
            secondRun += ruleRepository.insertMissingDefaultRules(type.name());
        }

        assertEquals(0, secondRun, "second run inserts nothing");
        assertEquals(afterFirst, ruleRepository.count());
    }

    @Test
    void insertMissingDefaultRules_leavesExistingDefaultUntouched() {
        var user = createUser("D");
        var existingType = NotificationType.defaults().get(0);
        // Pre-seed one default the way ensureDefaults would.
        ruleRepository.save(NotificationRule.builder()
                .user(user).type(existingType).isDefault(true).active(true).build());

        var insertedForExisting = ruleRepository.insertMissingDefaultRules(existingType.name());
        assertEquals(0, insertedForExisting, "user already has this default — nothing inserted");

        var otherType = NotificationType.defaults().get(1);
        var insertedForOther = ruleRepository.insertMissingDefaultRules(otherType.name());
        assertEquals(1, insertedForOther);
    }

    @Test
    void productScopedRuleDoesNotBlockDefaultBackfill() {
        // A user with only a product-scoped (non-default) rule of a type still needs the
        // null-product default backfilled — the NOT EXISTS clause checks product_id IS NULL.
        var defaultType = NotificationType.defaults().get(0);
        var user = createUser("E");
        var savedDefault = ruleRepository.insertMissingDefaultRules(defaultType.name());
        assertEquals(1, savedDefault);

        var ruleForUser = ruleRepository.findByUserIdAndTypeAndProductIsNull(user.getId(), defaultType);
        assertTrue(ruleForUser.isPresent());
        UUID ruleId = ruleForUser.get().getId();
        assertTrue(ruleForUser.get().isDefault());
        assertNull(ruleForUser.get().getProduct());
        assertNotNull(ruleId);
    }
}
