package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.NotificationResponse;
import com.relyon.economizaai.exception.NotificationNotFoundException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User-facing inbox for notifications already persisted by NotificationService.
 * Read state is per-user — household members each have their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private final NotificationRepository repository;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(User user, Pageable pageable) {
        return repository.findAllByUserIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(User user) {
        return repository.countByUserIdAndReadAtIsNull(user.getId());
    }

    @Transactional
    public void markRead(User user, UUID notificationId) {
        var notification = repository.findById(notificationId).orElseThrow(NotificationNotFoundException::new);
        if (!notification.getUser().getId().equals(user.getId())) {
            throw new NotificationNotFoundException();
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            repository.save(notification);
        }
    }

    @Transactional
    public int markAllRead(User user) {
        var marked = repository.markAllReadForUser(user.getId(), LocalDateTime.now());
        log.info("notification.mark_all_read user_id={} count={}", user.getId(), marked);
        return marked;
    }
}
