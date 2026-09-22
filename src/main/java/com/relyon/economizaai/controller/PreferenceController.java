package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.SetBrandPreferenceRequest;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.preferences.HouseholdPreferenceService;
import com.relyon.economizaai.service.preferences.ManualBrandPreferenceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/preferences")
@RequiredArgsConstructor
@Tag(name = "Preferences", description = "Auto-derived household pack-size and brand preferences (Phase 2.6), with manual brand overrides")
public class PreferenceController {

    private final HouseholdPreferenceService preferenceService;
    private final ManualBrandPreferenceService manualBrandPreferenceService;

    @GetMapping
    public ResponseEntity<List<HouseholdPreferenceResponse>> myPreferences(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(preferenceService.derivePreferences(user));
    }

    @PutMapping("/brand/{genericName}")
    public ResponseEntity<Void> setBrandPreference(@AuthenticationPrincipal User user,
                                                   @PathVariable String genericName,
                                                   @Valid @RequestBody SetBrandPreferenceRequest request) {
        manualBrandPreferenceService.set(user, genericName, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/brand/{genericName}")
    public ResponseEntity<Void> clearBrandPreference(@AuthenticationPrincipal User user,
                                                     @PathVariable String genericName) {
        manualBrandPreferenceService.clear(user, genericName);
        return ResponseEntity.noContent().build();
    }
}
