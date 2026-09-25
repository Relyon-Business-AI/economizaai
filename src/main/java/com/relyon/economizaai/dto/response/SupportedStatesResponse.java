package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * Which UFs the scan/import feature actually works for, for the FE to display
 * ("Funciona em: RS, SC, ..."). {@code verified} = dedicated adapter + real
 * fixture (announce these). {@code experimental} = best-effort catch-all we
 * attempt-and-capture but haven't proven yet (beta). Both are UF codes (e.g. "RS").
 */
public record SupportedStatesResponse(List<String> verified, List<String> experimental) {
}
