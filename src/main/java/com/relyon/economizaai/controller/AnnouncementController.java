package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.AnnouncementResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.announcement.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The announcement card on the Home screen: the one to show now, and closing it. */
@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "In-app announcement card (news, tips, notices)")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "Announcement to show now", description = "The oldest-started live announcement the user "
            + "has not dismissed; 204 when there is nothing to show.")
    @GetMapping("/current")
    public ResponseEntity<AnnouncementResponse> current(@AuthenticationPrincipal User user) {
        return announcementService.currentFor(user)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Dismiss an announcement", description = "Idempotent: dismissing twice is fine. Unknown id → 404.")
    @PostMapping("/{id}/dismissal")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        announcementService.dismiss(user, id);
        return ResponseEntity.noContent().build();
    }
}
