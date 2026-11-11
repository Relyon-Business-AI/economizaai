package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.StateHealthStatus;

/**
 * One state's scanning situation for the in-app FAQ ({@code GET /api/v1/sefaz/state-status}).
 *
 * @param uf     the UF code (e.g. "RS")
 * @param name   the state's name (e.g. "Rio Grande do Sul")
 * @param status WORKING, UNSTABLE or VALIDATING
 */
public record StateStatusResponse(String uf, String name, StateHealthStatus status) {
}
