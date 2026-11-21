package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.ChangeSettingRequest;
import com.relyon.economizaai.dto.response.AdminSettingResponse;
import com.relyon.economizaai.dto.response.AdminSettingSectionResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.settings.RuntimeSettings;
import com.relyon.economizaai.service.settings.SettingSection;
import io.swagger.v3.oas.annotations.Operation;
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

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
@Tag(name = "Admin · Configurações", description = "Regras de negócio ajustáveis sem deploy")
public class AdminSettingsController {

    private final RuntimeSettings runtimeSettings;

    @Operation(summary = "Lista todas as configurações, agrupadas por seção")
    @GetMapping
    public ResponseEntity<List<AdminSettingSectionResponse>> list() {
        var states = runtimeSettings.all();
        var sections = Arrays.stream(SettingSection.values())
                .map(section -> new AdminSettingSectionResponse(section.name(), section.getLabel(), section.getDescription(),
                        states.stream()
                                .filter(state -> state.setting().getSection() == section)
                                .map(AdminSettingResponse::from)
                                .toList()))
                .toList();
        return ResponseEntity.ok(sections);
    }

    @Operation(summary = "Altera uma configuração — vale na hora, sem deploy")
    @PutMapping("/{key}")
    public ResponseEntity<AdminSettingResponse> change(@PathVariable String key,
                                                       @Valid @RequestBody ChangeSettingRequest request,
                                                       @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(AdminSettingResponse.from(
                runtimeSettings.change(key, request.value(), admin.getEmail())));
    }

    @Operation(summary = "Volta uma configuração ao valor configurado para o ambiente")
    @DeleteMapping("/{key}")
    public ResponseEntity<AdminSettingResponse> restoreConfigured(@PathVariable String key,
                                                                 @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(AdminSettingResponse.from(
                runtimeSettings.restoreConfigured(key, admin.getEmail())));
    }
}
