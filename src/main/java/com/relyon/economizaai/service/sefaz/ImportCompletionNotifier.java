package com.relyon.economizaai.service.sefaz;

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

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells the user when their bulk import has finished. The import runs server-side
 * and can take a while (paced reconsults), so the user can close the tab — this is
 * what pings them when the whole batch is done.
 *
 * <p>Called by {@link ImportReconsultWorker} right after a nota reaches a terminal
 * reconsult state. Fires exactly once per wave: only when the user has NO more
 * in-flight import notas (queued/processing). The worker processes notas
 * sequentially, so exactly one nota observes the transition to zero — no duplicate.
 *
 * <p>Deliberately untransacted: {@link NotificationService#notify} performs the
 * outbound dispatch (email/push) and must not run inside a DB transaction.
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

    public void notifyIfBatchComplete(UUID userId) {
        if (userId == null) return;
        var inFlight = receiptRepository.countByUserIdAndOriginAndStatusIn(userId, ReceiptOrigin.IMPORT, IN_FLIGHT);
        if (inFlight > 0) return;

        var ready = receiptRepository.countByUserIdAndOriginAndStatus(
                userId, ReceiptOrigin.IMPORT, ReceiptStatus.PENDING_CONFIRMATION);
        var failed = receiptRepository.countByUserIdAndOriginAndStatus(
                userId, ReceiptOrigin.IMPORT, ReceiptStatus.FAILED_PARSE);
        if (ready + failed == 0) return; // nothing left to report (all confirmed/deleted)

        var user = userRepository.findById(userId).orElse(null);
        if (user == null) return;

        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var title = messageService.translate("receipt.import.complete.title", locale);
        var body = messageService.translate("receipt.import.complete.body", locale, ready, failed);
        notificationService.notify(new NotificationPayload(
                user, NotificationType.SYSTEM, title, body,
                Map.of("ready", ready, "failed", failed)));
        log.info("import.complete.notified user={} ready={} failed={}",
                LogMasker.email(user.getEmail()), ready, failed);
    }
}
