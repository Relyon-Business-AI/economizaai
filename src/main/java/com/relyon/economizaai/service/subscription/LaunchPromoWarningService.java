package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.LaunchPromoWarning;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.LaunchPromoWarningRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells launch-promo holders that their free Premium is about to end, inviting them to subscribe.
 * Runs once a day at a fixed daytime hour (Brasília) and notifies — inbox + push, through the
 * normal {@link NotificationService} — at each configured milestone (default D-15, D-7, D-3, D-1).
 *
 * <p>Exactly once per user per milestone: the {@link LaunchPromoWarning} row is claimed BEFORE
 * the notification goes out. The milestone is the current STAGE (the smallest configured value
 * still ≥ the days left), so a day the job did not run is caught up on the next one. Only users
 * whose Premium comes from the promo are warned — never a paid subscriber.
 *
 * <p>Not transactional: dispatch is outbound HTTP; each claim is its own short repository call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LaunchPromoWarningService {

    static final String EXTRA_KIND = "LAUNCH_PROMO_ENDING";
    private static final int PAGE_SIZE = 200;
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);
    private static final DateTimeFormatter DAY_AND_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private final CollaborativeProperties properties;
    private final SubscriptionRepository subscriptionRepository;
    private final LaunchPromoWarningRepository launchPromoWarningRepository;
    private final NotificationService notificationService;
    private final LocalizedMessageService localizedMessageService;

    @Scheduled(cron = "${economizaai.subscription.promo.warning-cron:0 0 10 * * *}", zone = "America/Sao_Paulo")
    public void run() {
        warnPromoHolders(BrazilClock.today());
    }

    void warnPromoHolders(LocalDate today) {
        var promo = properties.getSubscription().getPromo();
        if (!promo.isEnabled() || !promo.isWarningsEnabled() || promo.getUntil() == null) {
            return;
        }
        var daysLeft = ChronoUnit.DAYS.between(today, promo.getUntil());
        var milestone = currentMilestone(promo, daysLeft);
        if (milestone.isEmpty()) {
            return;
        }
        var warned = warnEveryHolder(promo.getUntil(), milestone.get(), daysLeft);
        log.info("launch_promo_warning.done milestone={} daysLeft={} warned={}", milestone.get(), daysLeft, warned);
    }

    /** The smallest configured milestone the promo has already reached; empty before the first one or after the end. */
    private Optional<Integer> currentMilestone(CollaborativeProperties.Subscription.Promo promo, long daysLeft) {
        if (daysLeft < 0) {
            return Optional.empty();
        }
        return promo.getWarningDays().stream()
                .filter(milestoneDays -> milestoneDays >= daysLeft)
                .min(Comparator.naturalOrder());
    }

    private int warnEveryHolder(LocalDate promoUntil, int milestoneDays, long daysLeft) {
        var promoEnd = EntitlementClock.endOfBrazilDay(promoUntil);
        var warned = 0;
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var page = subscriptionRepository.findLaunchPromoHoldersToWarn(
                    promoEnd, milestoneDays, afterId, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                return warned;
            }
            for (var subscription : page) {
                if (warnOne(subscription.getUser(), promoUntil, milestoneDays, daysLeft)) {
                    warned++;
                }
            }
            afterId = page.get(page.size() - 1).getId();
        }
    }

    /** One user's failure never stops the run. */
    private boolean warnOne(User user, LocalDate promoUntil, int milestoneDays, long daysLeft) {
        try {
            if (!claimMilestone(user, milestoneDays)) {
                return false;
            }
            notificationService.notify(buildPayload(user, promoUntil, milestoneDays, daysLeft));
            return true;
        } catch (RuntimeException failure) {
            log.warn("launch_promo_warning.failed user={} milestone={} reason={}",
                    LogMasker.email(user.getEmail()), milestoneDays, failure.getMessage());
            return false;
        }
    }

    /** False when another run already claimed this milestone for the user. */
    private boolean claimMilestone(User user, int milestoneDays) {
        try {
            launchPromoWarningRepository.saveAndFlush(LaunchPromoWarning.builder()
                    .userId(user.getId())
                    .milestoneDays(milestoneDays)
                    .build());
            return true;
        } catch (DataIntegrityViolationException alreadyClaimed) {
            return false;
        }
    }

    private NotificationPayload buildPayload(User user, LocalDate promoUntil, int milestoneDays, long daysLeft) {
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var lastDays = daysLeft <= 1;
        var endDay = promoUntil.format(DAY_AND_MONTH);
        var title = localizedMessageService.translate(
                lastDays ? "notification.launch_promo_ending.last_days.title" : "notification.launch_promo_ending.title",
                locale, endDay);
        var body = localizedMessageService.translate("notification.launch_promo_ending.body", locale, endDay);
        return new NotificationPayload(user, NotificationType.SYSTEM, title, body,
                Map.of("kind", EXTRA_KIND,
                        "validUntil", promoUntil.toString(),
                        "milestoneDays", milestoneDays));
    }
}
