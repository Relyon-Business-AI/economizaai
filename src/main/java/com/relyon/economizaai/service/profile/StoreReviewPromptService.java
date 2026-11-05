package com.relyon.economizaai.service.profile;

import com.relyon.economizaai.dto.response.StoreReviewPromptResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Decides when the app may show the native store-rating dialog. The ask must land on a
 * positive moment: the user just confirmed their Nth nota AND nothing failed for them
 * recently. The OS never reports whether the dialog was shown (and silently rate-limits
 * it), so every prompt is counted here and capped/cooled down across devices.
 */
@Slf4j
@Service
public class StoreReviewPromptService {

    static final String REASON_NOT_ENOUGH_RECEIPTS = "not_enough_receipts";
    static final String REASON_RECENT_FAILURE = "recent_failure";
    static final String REASON_MAX_PROMPTS = "max_prompts_reached";
    static final String REASON_COOLDOWN = "cooldown";

    /** From the user's point of view these all mean "my nota didn't work". */
    private static final Set<ReceiptStatus> FAILURE_STATUSES =
            Set.of(ReceiptStatus.FAILED_PARSE, ReceiptStatus.CONTINGENCY_PENDING, ReceiptStatus.NEEDS_DEVICE_FETCH,
                    ReceiptStatus.PORTAL_PENDING);

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;
    private final int minConfirmedReceipts;
    private final int failureLookbackDays;
    private final int maxPrompts;
    private final int cooldownDays;

    public StoreReviewPromptService(ReceiptRepository receiptRepository,
                                    UserRepository userRepository,
                                    @Value("${economizaai.store-review.min-confirmed-receipts:2}") int minConfirmedReceipts,
                                    @Value("${economizaai.store-review.failure-lookback-days:7}") int failureLookbackDays,
                                    @Value("${economizaai.store-review.max-prompts:3}") int maxPrompts,
                                    @Value("${economizaai.store-review.cooldown-days:90}") int cooldownDays) {
        this.receiptRepository = receiptRepository;
        this.userRepository = userRepository;
        this.minConfirmedReceipts = minConfirmedReceipts;
        this.failureLookbackDays = failureLookbackDays;
        this.maxPrompts = maxPrompts;
        this.cooldownDays = cooldownDays;
    }

    @Transactional(readOnly = true)
    public StoreReviewPromptResponse evaluate(User user) {
        var response = decide(user);
        log.info("store_review.evaluated user={} eligible={} reason={}",
                LogMasker.email(user.getEmail()), response.eligible(), response.reason());
        return response;
    }

    private StoreReviewPromptResponse decide(User user) {
        if (user.getStoreReviewPromptCount() >= maxPrompts) {
            return StoreReviewPromptResponse.blocked(REASON_MAX_PROMPTS);
        }
        if (isInCooldown(user)) {
            return StoreReviewPromptResponse.blocked(REASON_COOLDOWN);
        }
        if (receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED) < minConfirmedReceipts) {
            return StoreReviewPromptResponse.blocked(REASON_NOT_ENOUGH_RECEIPTS);
        }
        if (hadRecentFailure(user)) {
            return StoreReviewPromptResponse.blocked(REASON_RECENT_FAILURE);
        }
        return StoreReviewPromptResponse.allowed();
    }

    private boolean isInCooldown(User user) {
        var lastPromptedAt = user.getStoreReviewLastPromptedAt();
        return lastPromptedAt != null && lastPromptedAt.isAfter(LocalDateTime.now().minusDays(cooldownDays));
    }

    private boolean hadRecentFailure(User user) {
        var since = LocalDateTime.now().minusDays(failureLookbackDays);
        return receiptRepository.countByUserIdAndStatusInAndUpdatedAtGreaterThanEqual(
                user.getId(), FAILURE_STATUSES, since) > 0;
    }

    /** The app called the native dialog — count it regardless of whether the OS showed it. */
    @Transactional
    public void recordPrompted(User user) {
        user.setStoreReviewPromptCount(user.getStoreReviewPromptCount() + 1);
        user.setStoreReviewLastPromptedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("store_review.prompted user={} count={}",
                LogMasker.email(user.getEmail()), user.getStoreReviewPromptCount());
    }
}
