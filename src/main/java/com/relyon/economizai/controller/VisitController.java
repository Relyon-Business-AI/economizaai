package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.VisitBeaconRequest;
import com.relyon.economizai.security.ratelimit.ClientIpResolver;
import com.relyon.economizai.service.attribution.VisitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated top-of-funnel beacon. The web landing POSTs one visit
 * per session on first load; we store it anonymously to measure click→signup
 * conversion per campaign. Rate-limited per IP by {@code RateLimitFilter}.
 */
@RestController
@RequestMapping("/api/v1/visits")
@RequiredArgsConstructor
@Tag(name = "Visits", description = "Anonymous top-of-funnel tracking")
public class VisitController {

    private final VisitService visitService;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Record an anonymous landing visit (fire-and-forget beacon).")
    public void beacon(@RequestBody VisitBeaconRequest request, HttpServletRequest httpRequest) {
        visitService.record(request, ClientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
    }
}
