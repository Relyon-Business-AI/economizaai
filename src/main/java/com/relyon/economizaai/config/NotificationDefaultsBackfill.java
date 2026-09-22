package com.relyon.economizaai.config;

import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proactively materializes the missing default notification rules for every
 * existing user on startup.
 *
 * <p>New users get their default rules seeded eagerly (registration / OAuth) and
 * lazily on first list. Triggers already work without the rows ({@code isEnabled}
 * treats an absent default as enabled), but the screen only shows a toggle once
 * the row exists — so existing accounts wouldn't see them until they opened it.
 *
 * <p>This backfill closes that gap in a few bulk INSERTs (one per default type),
 * each skipping users who already have the rule. Idempotent — a second run
 * inserts nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDefaultsBackfill implements ApplicationRunner {

    private final NotificationRuleRepository ruleRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var total = 0;
        for (var type : NotificationType.defaults()) {
            var inserted = ruleRepository.insertMissingDefaultRules(type.name());
            total += inserted;
            log.info("notification.defaults.backfill type={} inserted={}", type, inserted);
        }
        log.info("notification.defaults.backfill done types={} inserted={}",
                NotificationType.defaults().size(), total);
    }
}
