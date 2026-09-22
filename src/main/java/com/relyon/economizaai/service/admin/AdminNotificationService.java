package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.SendTestNotificationRequest;
import com.relyon.economizaai.dto.response.AdminNotificationSummaryResponse;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * Admin-only test harness for the notification pipeline. Resolves a user
 * by email and hands a payload to {@link NotificationService} — useful for
 * smoke-testing FCM/SMTP wiring on demand without waiting for a natural
 * trigger (promo detection, stockout, etc).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private static final String DEFAULT_TITLE = "economizai test";
    private static final String DEFAULT_BODY = "If you can read this, push notifications are working.";

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;

    /** Cross-user list of sent notifications over the window — the admin "enviadas" view. */
    @Transactional(readOnly = true)
    public Page<AdminNotificationSummaryResponse> listSent(int days, Pageable pageable) {
        var since = LocalDate.now().minusDays(Math.max(1, days) - 1L).atStartOfDay();
        var page = notificationRepository.findSentSince(since, pageable);
        log.info("admin.notification.list_sent days={} total={}", days, page.getTotalElements());
        return page.map(AdminNotificationSummaryResponse::from);
    }

    public void sendTest(SendTestNotificationRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException(request.email()));
        var type = request.type() != null ? request.type() : NotificationType.SYSTEM;
        var title = request.title() != null && !request.title().isBlank() ? request.title() : DEFAULT_TITLE;
        var body = request.body() != null && !request.body().isBlank() ? request.body() : DEFAULT_BODY;
        log.info("admin.notification.test_send target={} type={} hasPushToken={}",
                LogMasker.email(user.getEmail()), type,
                user.getPushDeviceToken() != null && !user.getPushDeviceToken().isBlank());
        notificationService.notify(new NotificationPayload(user, type, title, body, Map.of("source", "admin_test")));
    }
}
