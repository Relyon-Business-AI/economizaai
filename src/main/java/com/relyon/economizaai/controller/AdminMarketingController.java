package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.MarketingDashboardResponse;
import com.relyon.economizaai.service.analytics.MarketingDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/marketing")
@RequiredArgsConstructor
public class AdminMarketingController {

    private final MarketingDashboardService marketingDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingDashboardResponse dashboard(@RequestParam(defaultValue = "30") int days) {
        return marketingDashboardService.dashboard(days);
    }
}
