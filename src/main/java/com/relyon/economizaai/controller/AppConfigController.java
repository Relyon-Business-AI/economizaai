package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.AppConfigResponse;
import com.relyon.economizaai.service.AppConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/app-config")
@RequiredArgsConstructor
@Tag(name = "App config", description = "Remote config for the mobile force-update gate (publicly accessible)")
public class AppConfigController {

    /** Short client cache: a kill-switch flip must propagate within a minute. */
    private static final CacheControl SHORT_CACHE = CacheControl.maxAge(Duration.ofSeconds(60));

    private final AppConfigService appConfigService;

    @GetMapping
    public ResponseEntity<AppConfigResponse> current() {
        return ResponseEntity.ok().cacheControl(SHORT_CACHE).body(appConfigService.current());
    }
}
