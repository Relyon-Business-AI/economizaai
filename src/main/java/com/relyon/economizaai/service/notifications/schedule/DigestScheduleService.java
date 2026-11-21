package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves each user's effective deals-digest send hour (0-23, wall-clock
 * America/Sao_Paulo — see DEV_NOTES for the v1 single-timezone assumption),
 * in three layers:
 *
 * <ol>
 *   <li>the user's explicit {@code digestSendHour} override, if set;</li>
 *   <li>else the household's modal confirmed-receipt shopping hour minus 1h,
 *       but only with {@link #minReceiptsForInference}+ receipts that carry a
 *       non-null issued_at (so we catch them just before they usually shop);</li>
 *   <li>else a per-user default spread over {@link #spreadStartHour}:00 to
 *       {@link #spreadEndHour}:00, derived from the user id — stable for the
 *       user, and new accounts do not all land on the same hour. A window that
 *       ends before it starts crosses midnight.</li>
 * </ol>
 *
 * <p>The result is stored in {@code User.digestEffectiveSendHour}, so the digest
 * job selects due users in SQL instead of recomputing the hour for everyone
 * every hour. Hour math wraps around: hour 0 minus 1 becomes 23.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestScheduleService {

    private static final int HOURS_IN_DAY = 24;
    private static final int PAGE_SIZE = 200;
    /** Sorts before every real id — the starting point of the keyset pagination. */
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);

    /** First and last hour (inclusive) of the default-hour spread. */
    @Getter @Setter
    @Value("${economizaai.notifications.deals-digest.default-hour-from:11}")
    private volatile int spreadStartHour = 11;
    @Getter @Setter
    @Value("${economizaai.notifications.deals-digest.default-hour-to:19}")
    private volatile int spreadEndHour = 19;
    /** Below this many issued-at receipts, the modal-hour inference is too noisy. */
    @Getter @Setter
    @Value("${economizaai.notifications.deals-digest.min-receipts-for-inference:5}")
    private volatile int minReceiptsForInference = 5;

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public int effectiveSendHour(User user) {
        var override = user.getDigestSendHour();
        if (override != null) return override;
        return inferredSendHour(user).orElseGet(() -> spreadDefaultHour(user.getId()));
    }

    /** The household's usual shopping hour minus one, when there is enough history to tell. */
    private Optional<Integer> inferredSendHour(User user) {
        var householdId = user.getHousehold() != null ? user.getHousehold().getId() : null;
        if (householdId == null) return Optional.empty();
        var histogram = receiptRepository.findConfirmedIssuedHourHistogram(householdId);
        var totalReceipts = histogram.stream().mapToLong(ReceiptRepository.HourCount::getReceiptCount).sum();
        if (totalReceipts < minReceiptsForInference) return Optional.empty();
        return histogram.stream()
                .max(Comparator.comparingLong(ReceiptRepository.HourCount::getReceiptCount)
                        .thenComparing(ReceiptRepository.HourCount::getHourOfDay))
                .map(modal -> wrap(modal.getHourOfDay() - 1));
    }

    /** Deterministic per-user default inside the spread window. */
    int spreadDefaultHour(UUID userId) {
        var firstHour = spreadStartHour;
        var hoursInWindow = Math.floorMod(spreadEndHour - firstHour, HOURS_IN_DAY) + 1;
        return wrap(firstHour + Math.floorMod(userId.hashCode(), hoursInWindow));
    }

    /**
     * Gives a send hour to every digest recipient that has none yet (new accounts, or an
     * override that was just cleared), page by page. Returns how many were assigned.
     */
    public int assignMissingSendHours() {
        var assigned = 0;
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var users = userRepository.findDigestUsersWithoutSendHour(afterId, PageRequest.of(0, PAGE_SIZE));
            if (users.isEmpty()) break;
            for (var user : users) {
                if (assignSendHourIsolated(user)) assigned++;
            }
            afterId = users.get(users.size() - 1).getId();
        }
        if (assigned > 0) log.info("digest.send_hour.assigned users={}", assigned);
        return assigned;
    }

    private boolean assignSendHourIsolated(User user) {
        try {
            var sendHour = effectiveSendHour(user);
            userRepository.updateDigestEffectiveSendHour(user.getId(), sendHour);
            user.setDigestEffectiveSendHour(sendHour);
            return true;
        } catch (RuntimeException ex) {
            log.warn("digest.send_hour.failed user={} reason={}", LogMasker.email(user.getEmail()), ex.getMessage());
            return false;
        }
    }

    /**
     * Keeps the stored hour following the household's habits: once there is enough history,
     * the inferred hour replaces the default one. An explicit override is never touched.
     */
    public void updateSendHourFromShoppingHabits(User user) {
        if (user.getDigestSendHour() != null) return;
        var inferred = inferredSendHour(user);
        if (inferred.isEmpty() || inferred.get().equals(user.getDigestEffectiveSendHour())) return;
        userRepository.updateDigestEffectiveSendHour(user.getId(), inferred.get());
        user.setDigestEffectiveSendHour(inferred.get());
        log.info("digest.send_hour.inferred user={} hour={}", LogMasker.email(user.getEmail()), inferred.get());
    }

    /** Keep an hour within 0-23, wrapping (-1 -> 23). */
    private int wrap(int hour) {
        return Math.floorMod(hour, HOURS_IN_DAY);
    }
}
