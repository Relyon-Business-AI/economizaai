package com.relyon.economizaai.dto.response;

import java.util.List;

/** Chaves de acesso found in an uploaded export file (CSV/Excel/PDF) — no side effects. */
public record ExtractedChavesResponse(List<String> chaves) {}
