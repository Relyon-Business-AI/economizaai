package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.StateStatusResponse;
import com.relyon.economizaai.dto.response.SupportedStatesResponse;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.service.sefaz.StateStatusService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Read-only list of the states the scan/import feature supports, so the FE can
 * tell the user up front which UFs work ("Funciona em: RS, SC, ..."). Authenticated
 * but not admin — it's product info, not sensitive. The source of truth is the
 * registered adapters, so this never drifts from what the pipeline actually handles.
 */
@RestController
@RequestMapping("/api/v1/sefaz")
@RequiredArgsConstructor
@Tag(name = "SEFAZ", description = "State coverage for receipt scanning/import")
public class SupportedStatesController {

    private final SefazIngestionService sefazIngestionService;
    private final StateStatusService stateStatusService;

    @GetMapping("/supported-states")
    public ResponseEntity<SupportedStatesResponse> supportedStates() {
        return ResponseEntity.ok(new SupportedStatesResponse(
                sorted(sefazIngestionService.getVerifiedStates()),
                sorted(sefazIngestionService.experimentalStates())));
    }

    /**
     * How scanning is going in each of the 27 states right now (WORKING / UNSTABLE /
     * VALIDATING) — the source of the in-app FAQ's "situação dos estados", so the app never
     * hardcodes it. Authenticated like {@code /supported-states}; cached server-side.
     */
    @GetMapping("/state-status")
    public ResponseEntity<List<StateStatusResponse>> stateStatus() {
        return ResponseEntity.ok(stateStatusService.currentStatus());
    }

    private static List<String> sorted(Set<UnidadeFederativa> states) {
        return states.stream().map(Enum::name).sorted(Comparator.naturalOrder()).toList();
    }
}
