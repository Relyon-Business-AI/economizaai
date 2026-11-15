package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SaveAnnouncementRequest;
import com.relyon.economizaai.dto.response.AdminAnnouncementResponse;
import com.relyon.economizaai.service.admin.AdminAnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD of the in-app announcements shown on the app's Home screen. ADMIN-only via
 * SecurityConfig ({@code /api/v1/admin/**}).
 */
@Tag(name = "Admin - Announcements")
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AdminAnnouncementService announcementService;

    @Operation(summary = "List announcements", description = "Newest first, each with its computed status "
            + "(SCHEDULED/LIVE/ENDED/INACTIVE) and how many users dismissed it.")
    @GetMapping
    public ResponseEntity<List<AdminAnnouncementResponse>> list() {
        return ResponseEntity.ok(announcementService.list());
    }

    @Operation(summary = "Create an announcement", description = "startsAt null → now; endsAt null → until switched off. "
            + "actionLabel and destination come together or not at all; endsAt must be after startsAt.")
    @PostMapping
    public ResponseEntity<AdminAnnouncementResponse> create(@Valid @RequestBody SaveAnnouncementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(announcementService.create(request));
    }

    @Operation(summary = "Update an announcement", description = "Full replace, same rules as create.")
    @PutMapping("/{id}")
    public ResponseEntity<AdminAnnouncementResponse> update(@PathVariable UUID id,
                                                            @Valid @RequestBody SaveAnnouncementRequest request) {
        return ResponseEntity.ok(announcementService.update(id, request));
    }

    @Operation(summary = "Delete an announcement", description = "Its dismissals go with it.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        announcementService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
