package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends what the quiet hours held back. Runs every hour and does nothing while the
 * window is still open, so the first run after it ends (08:00 by default) delivers the
 * night's notifications — and a later run picks up anything a restart left behind.
 *
 * <p>Per user: oldest first, at most {@link QuietHoursPolicy#getMaxDeferredPerUser()}
 * pushes; the rest stay inbox-only so nobody wakes up to a burst. Each row is claimed
 * before it is sent, and one user's failure never stops the others.
 */
@Slf4j
@Service
public class DeferredNotificationDrainer {

    static final String REASON_OVER_CAP = "quiet_hours_cap_inbox_only";
    private static final int USER_PAGE_SIZE = 200;

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final QuietHoursPolicy quietHours;
    private final Executor dispatchExecutor;

    public DeferredNotificationDrainer(NotificationRepository notificationRepository,
                                       NotificationService notificationService,
                                       QuietHoursPolicy quietHours,
                                       @Qualifier(AsyncConfig.NOTIFICATION_DISPATCH_EXECUTOR) Executor dispatchExecutor) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.quietHours = quietHours;
        this.dispatchExecutor = dispatchExecutor;
    }

    @Scheduled(cron = "${economizaai.notifications.quiet-hours.drain-cron:0 0 * * * *}",
            zone = "America/Sao_Paulo")
    public void drainWhenWindowIsOver() {
        if (quietHours.isQuietNow()) return;
        try {
            dispatchExecutor.execute(this::drain);
        } catch (RejectedExecutionException rejection) {
            // Rows stay held back; the next hourly run takes them.
            log.warn("notification.quiet_hours.drain_rejected reason=pool_full");
        }
    }

    /** Drains every user with held-back notifications, one page of users at a time. */
    public void drain() {
        var visitedUserIds = new HashSet<UUID>();
        var pushed = 0;
        var inboxOnly = 0;
        while (true) {
            var userIds = notificationRepository.findUserIdsWithDeferredDispatch(PageRequest.of(0, USER_PAGE_SIZE));
            var newUserIds = userIds.stream().filter(visitedUserIds::add).toList();
            // Drained users drop out of the query; a page with nobody new means the rest keep failing.
            if (newUserIds.isEmpty()) break;
            for (var userId : newUserIds) {
                try {
                    var outcome = drainUser(userId);
                    pushed += outcome.pushed();
                    inboxOnly += outcome.inboxOnly();
                } catch (RuntimeException ex) {
                    log.warn("notification.quiet_hours.user_failed user_id={} reason={}", userId, ex.getMessage());
                }
            }
        }
        if (!visitedUserIds.isEmpty()) {
            log.info("notification.quiet_hours.drain done users={} pushed={} inbox_only={}",
                    visitedUserIds.size(), pushed, inboxOnly);
        }
    }

    private UserOutcome drainUser(UUID userId) {
        var pushed = 0;
        var inboxOnly = 0;
        for (var heldBack : notificationRepository.findDeferredByUserId(userId)) {
            if (notificationRepository.claimDeferredDispatch(heldBack.getId()) == 0) continue;
            if (pushed < quietHours.getMaxDeferredPerUser()) {
                sendIsolated(heldBack);
                pushed++;
            } else {
                notificationService.keepInboxOnly(heldBack, REASON_OVER_CAP);
                inboxOnly++;
            }
        }
        return new UserOutcome(pushed, inboxOnly);
    }

    /** A failed send is logged and skipped — the row is already claimed, the user's other rows go on. */
    private void sendIsolated(Notification heldBack) {
        try {
            notificationService.dispatchHeldBack(heldBack);
        } catch (RuntimeException ex) {
            log.warn("notification.quiet_hours.send_failed notification={} reason={}",
                    heldBack.getId(), ex.getMessage());
        }
    }

    private record UserOutcome(int pushed, int inboxOnly) {}
}
