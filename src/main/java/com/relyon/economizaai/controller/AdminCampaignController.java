package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SaveAudienceRequest;
import com.relyon.economizaai.dto.request.SaveCampaignRequest;
import com.relyon.economizaai.dto.response.AudiencePreviewResponse;
import com.relyon.economizaai.dto.response.AudienceResponse;
import com.relyon.economizaai.dto.response.CampaignMetricsResponse;
import com.relyon.economizaai.dto.response.CampaignResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.admin.AdminAudienceService;
import com.relyon.economizaai.service.admin.AdminCampaignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * Admin notification ops: audiences (reusable user segments) and campaigns
 * (authored sends with per-campaign conversion metrics). ADMIN-only via
 * SecurityConfig ({@code /api/v1/admin/**}).
 */
@Tag(name = "Admin - Notification campaigns")
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
public class AdminCampaignController {

    private final AdminAudienceService audienceService;
    private final AdminCampaignService campaignService;

    // ── Audiences ──

    @Operation(summary = "List audiences", description = "All audiences (built-in first) with the LIVE count of matching users.")
    @GetMapping("/audiences")
    public ResponseEntity<List<AudienceResponse>> listAudiences() {
        return ResponseEntity.ok(audienceService.list());
    }

    @Operation(summary = "Get one audience")
    @GetMapping("/audiences/{id}")
    public ResponseEntity<AudienceResponse> getAudience(@PathVariable UUID id) {
        return ResponseEntity.ok(audienceService.get(id));
    }

    @Operation(summary = "Create an audience", description = "Null filter fields mean \"don't filter on this\"; non-null ones combine with AND.")
    @PostMapping("/audiences")
    public ResponseEntity<AudienceResponse> createAudience(@Valid @RequestBody SaveAudienceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(audienceService.create(request));
    }

    @Operation(summary = "Update an audience", description = "Built-in audiences (e.g. Admins) are locked and return 400.")
    @PutMapping("/audiences/{id}")
    public ResponseEntity<AudienceResponse> updateAudience(@PathVariable UUID id,
                                                           @Valid @RequestBody SaveAudienceRequest request) {
        return ResponseEntity.ok(audienceService.update(id, request));
    }

    @Operation(summary = "Delete an audience", description = "Built-in → 400; referenced by any campaign → 409.")
    @DeleteMapping("/audiences/{id}")
    public ResponseEntity<Void> deleteAudience(@PathVariable UUID id) {
        audienceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Preview an audience", description = "Who it resolves to RIGHT NOW: total count + sample recipient emails.")
    @GetMapping("/audiences/{id}/preview")
    public ResponseEntity<AudiencePreviewResponse> previewAudience(@PathVariable UUID id) {
        return ResponseEntity.ok(audienceService.preview(id));
    }

    // ── Campaigns ──

    @Operation(summary = "List campaigns", description = "Newest first; every row carries the comparable metrics block "
            + "(sent/delivered/read/opened/converted + rates) so texts, times and types rank against each other.")
    @GetMapping("/campaigns")
    public ResponseEntity<Page<CampaignResponse>> listCampaigns(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(campaignService.list(pageable));
    }

    @Operation(summary = "Get one campaign")
    @GetMapping("/campaigns/{id}")
    public ResponseEntity<CampaignResponse> getCampaign(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.get(id));
    }

    @Operation(summary = "Create a campaign", description = "scheduledAt null → DRAFT; future instant → SCHEDULED "
            + "(dispatched automatically at that time). Past instant → 400.")
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@AuthenticationPrincipal User admin,
                                                           @Valid @RequestBody SaveCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(request, admin.getEmail()));
    }

    @Operation(summary = "Update a campaign", description = "Only DRAFT/SCHEDULED campaigns are editable.")
    @PutMapping("/campaigns/{id}")
    public ResponseEntity<CampaignResponse> updateCampaign(@PathVariable UUID id,
                                                           @Valid @RequestBody SaveCampaignRequest request) {
        return ResponseEntity.ok(campaignService.update(id, request));
    }

    @Operation(summary = "Send a campaign now", description = "Queues the campaign for immediate dispatch (the scheduler "
            + "poll picks it up within ~15s). Allowed from DRAFT, SCHEDULED or FAILED (retry).")
    @PostMapping("/campaigns/{id}/send")
    public ResponseEntity<CampaignResponse> sendCampaign(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(campaignService.send(id));
    }

    @Operation(summary = "Cancel a campaign", description = "DRAFT/SCHEDULED only — an in-flight or completed send cannot be cancelled.")
    @PostMapping("/campaigns/{id}/cancel")
    public ResponseEntity<CampaignResponse> cancelCampaign(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.cancel(id));
    }

    @Operation(summary = "Test-send to yourself", description = "Dispatches the campaign's content to the CALLING admin only, "
            + "untagged — test sends never pollute the campaign's metrics. Allowed in any status.")
    @PostMapping("/campaigns/{id}/test")
    public ResponseEntity<Void> testCampaign(@AuthenticationPrincipal User admin, @PathVariable UUID id) {
        campaignService.sendTest(id, admin);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Delete a campaign", description = "DRAFT/CANCELLED/FAILED only — SENT campaigns are history.")
    @DeleteMapping("/campaigns/{id}")
    public ResponseEntity<Void> deleteCampaign(@PathVariable UUID id) {
        campaignService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Campaign metrics", description = "Full drill-down: funnel + engagement + attributed conversions "
            + "(CONVERTED events by recipients inside the attribution window after the send) + every event type.")
    @GetMapping("/campaigns/{id}/metrics")
    public ResponseEntity<CampaignMetricsResponse> campaignMetrics(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.metrics(id));
    }
}
