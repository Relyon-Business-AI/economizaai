package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.DashboardResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.dashboard.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "App-open snapshot — bundles spend + recent receipts + suggested list + community promos + unread count in one call")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ResponseEntity<DashboardResponse> dashboard(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(dashboardService.build(user));
    }
}
