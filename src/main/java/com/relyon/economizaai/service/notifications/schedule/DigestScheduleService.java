package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/**
 * Resolves each user's effective deals-digest send hour (0-23, wall-clock
 * America/Sao_Paulo — see DEV_NOTES for the v1 single-timezone assumption),
 * in three layers:
 *
 * <ol>
 *   <li>the user's explicit {@code digestSendHour} override, if set;</li>
 *   <li>else the household's modal confirmed-receipt shopping hour minus 1h,
 *       but only with {@link #MIN_RECEIPTS_FOR_INFERENCE}+ receipts that carry a
 *       non-null issued_at (so we catch them just before they usually shop);</li>
 *   <li>else {@link #DEFAULT_SEND_HOUR} — a researched Brazilian after-work
 *       shopping default.</li>
 * </ol>
 *
 * <p>Hour math wraps around: hour 0 minus 1 becomes 23.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestScheduleService {

    /** Researched BR after-work shopping default (16:00). */
    static final int DEFAULT_SEND_HOUR = 16;
    /** Below this many issued-at receipts, the modal-hour inference is too noisy. */
    static final int MIN_RECEIPTS_FOR_INFERENCE = 5;

    private final ReceiptRepository receiptRepository;

    @Transactional(readOnly = true)
    public int effectiveSendHour(User user) {
        var override = user.getDigestSendHour();
        if (override != null) return override;

        var householdId = user.getHousehold() != null ? user.getHousehold().getId() : null;
        if (householdId != null) {
            var histogram = receiptRepository.findConfirmedIssuedHourHistogram(householdId);
            var totalReceipts = histogram.stream().mapToLong(ReceiptRepository.HourCount::getReceiptCount).sum();
            if (totalReceipts >= MIN_RECEIPTS_FOR_INFERENCE) {
                var modalHour = histogram.stream()
                        .max(Comparator.comparingLong(ReceiptRepository.HourCount::getReceiptCount)
                                .thenComparing(ReceiptRepository.HourCount::getHourOfDay))
                        .map(ReceiptRepository.HourCount::getHourOfDay)
                        .orElse(DEFAULT_SEND_HOUR);
                return wrap(modalHour - 1);
            }
        }
        return DEFAULT_SEND_HOUR;
    }

    /** Keep an hour within 0-23, wrapping (-1 -> 23). */
    private int wrap(int hour) {
        return ((hour % 24) + 24) % 24;
    }
}
