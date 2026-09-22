package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.CreateNotificationRuleRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationRuleRequest;
import com.relyon.economizaai.dto.response.NotificationRuleResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.notifications.NotificationRuleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Unified notification-rule management. Create user rules (price ceiling,
 * price drop, replenishment, budget) and enable/disable any rule — including
 * the auto-seeded system defaults (personal/community promo, cheaper market,
 * digest), which are returned by the list endpoint as toggleable entries.
 */
@RestController
@RequestMapping("/api/v1/notification-rules")
@RequiredArgsConstructor
@Tag(name = "Notification Rules", description = "Create/list/enable/disable notification rules (user-created + defaults)")
public class NotificationRuleController {

    private final NotificationRuleService ruleService;

    @GetMapping
    public ResponseEntity<List<NotificationRuleResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ruleService.list(user));
    }

    @PostMapping
    public ResponseEntity<NotificationRuleResponse> create(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody CreateNotificationRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.create(user, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<NotificationRuleResponse> update(@AuthenticationPrincipal User user,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody UpdateNotificationRuleRequest request) {
        return ResponseEntity.ok(ruleService.update(user, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        ruleService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
