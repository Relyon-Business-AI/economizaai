package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells the user when their bulk import has finished. The import runs server-side
 * and can take a while (paced reconsults), so the user can close the tab — this is
 * what pings them when the whole batch is done.
 *
 * <p>Runs as a sweep on the {@link ImportReconsultWorker} tick (ingests finish
 * asynchronously, so a per-nota check right after dispatch always saw the row
 * still PROCESSING and never fired). Each tick finds users whose import results
 * are newer than their notification claim, and for each one checks — in a short
 * claim transaction — that NO import nota is still in flight (queued/processing).
 *
 * <p>Idempotent by construction: the claim ({@code User.importCompletionNotifiedAt})
 * stores the {@code updatedAt} of the newest terminal import nota already
 * notified. A finished wave is announced once; a NEW wave produces newer terminal
 * rows and earns a fresh notification. The dispatch itself (Expo/SMTP) runs
 * OUTSIDE the claim transaction — outbound HTTP never holds a DB connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportCompletionNotifier {

    private static final Set<ReceiptStatus> IN_FLIGHT =
            EnumSet.of(ReceiptStatus.IMPORT_QUEUED, ReceiptStatus.PROCESSING);

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;
    private final TransactionTemplate transactionTemplate;

    /** One sweep per worker tick: check every user with unnotified import results. */
    public void notifyCompletedBatches() {
        var candidateUserIds = receiptRepository.findUsersWithUnnotifiedImportResults();
        for (var userId : candidateUserIds) {
            notifyIfBatchComplete(userId);
        }
    }

    public void notifyIfBatchComplete(UUID userId) {
        if (userId == null) return;
        var completion = claimCompletedBatch(userId);
        if (completion == null) return;
        dispatch(completion);
    }

    /**
     * The COLLECT half, in one short transaction: verify the batch is really done
     * (zero in-flight import notas), that its newest terminal nota is newer than
     * the last claim, and stamp the claim BEFORE dispatching — so a crash between
     * claim and dispatch loses one ping instead of ever duplicating it.
     * Returns null when there is nothing (new) to announce.
     */
    private BatchCompletion claimCompletedBatch(UUID userId) {
        return transactionTemplate.execute(txStatus -> {
            var inFlight = receiptRepository.countByUserIdAndOriginAndStatusIn(
                    userId, ReceiptOrigin.IMPORT, IN_FLIGHT);
            if (inFlight > 0) return null;

            var newestTerminalAt = receiptRepository.latestTerminalImportUpdatedAt(userId);
            if (newestTerminalAt == null) return null; // nothing left to report (all confirmed/deleted)

            var user = userRepository.findById(userId).orElse(null);
            if (user == null) return null;
            var alreadyNotified = user.getImportCompletionNotifiedAt() != null
                    && !newestTerminalAt.isAfter(user.getImportCompletionNotifiedAt());
            if (alreadyNotified) return null;

            var ready = receiptRepository.countByUserIdAndOriginAndStatus(
                    userId, ReceiptOrigin.IMPORT, ReceiptStatus.PENDING_CONFIRMATION);
            var failed = receiptRepository.countByUserIdAndOriginAndStatus(
                    userId, ReceiptOrigin.IMPORT, ReceiptStatus.FAILED_PARSE);
            if (ready + failed == 0) return null;

            user.setImportCompletionNotifiedAt(newestTerminalAt);
            userRepository.save(user);
            return new BatchCompletion(user, ready, failed);
        });
    }

    /** The DISPATCH half — untransacted outbound notification. */
    private void dispatch(BatchCompletion completion) {
        var user = completion.user();
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var title = messageService.translate("receipt.import.complete.title", locale);
        var body = messageService.translate("receipt.import.complete.body", locale,
                completion.ready(), completion.failed());
        notificationService.notify(new NotificationPayload(
                user, NotificationType.SYSTEM, title, body,
                Map.of("ready", completion.ready(), "failed", completion.failed())));
        log.info("import.complete.notified user={} ready={} failed={}",
                LogMasker.email(user.getEmail()), completion.ready(), completion.failed());
    }

    private record BatchCompletion(User user, long ready, long failed) {}
}
