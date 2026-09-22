package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.RecordNotificationEventRequest;
import com.relyon.economizaai.dto.response.NotificationResponse;
import com.relyon.economizaai.exception.InvalidNotificationEventException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import com.relyon.economizaai.service.notifications.NotificationInboxService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "User-facing notification inbox (list / unread count / mark read)")
public class NotificationController {

    private final NotificationInboxService inbox;
    private final NotificationEventService eventService;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(@AuthenticationPrincipal User user,
                                                           @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(inbox.list(user, pageable));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("unread", inbox.unreadCount(user)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        inbox.markRead(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mark-all-read")
    public ResponseEntity<Map<String, Integer>> markAllRead(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("marked", inbox.markAllRead(user)));
    }

    /**
     * App client reports a behavioral telemetry event (push opened, deal
     * viewed/tapped, added to list, dismissed/muted, ...). Scoped to the
     * authenticated user. Server-only and unknown event types are rejected.
     */
    @PostMapping("/events")
    public ResponseEntity<Void> recordEvent(@AuthenticationPrincipal User user,
                                            @Valid @RequestBody RecordNotificationEventRequest request) {
        var type = parseClientReportable(request.type());
        eventService.record(user, type, RecordContext.builder()
                .notificationId(request.notificationId())
                .productId(request.productId())
                .marketCnpj(request.marketCnpj())
                .build());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private NotificationEventType parseClientReportable(String rawType) {
        NotificationEventType type;
        try {
            type = NotificationEventType.valueOf(rawType);
        } catch (IllegalArgumentException ex) {
            throw new InvalidNotificationEventException(rawType);
        }
        if (!type.isClientReportable()) {
            throw new InvalidNotificationEventException(rawType);
        }
        return type;
    }
}
