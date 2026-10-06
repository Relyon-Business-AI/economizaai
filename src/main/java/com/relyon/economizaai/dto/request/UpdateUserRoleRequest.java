package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Admin change of a user's role. Only USER ↔ MERCHANT via API — anything
 * touching ADMIN (promote or demote) is refused.
 */
public record UpdateUserRoleRequest(
        @Schema(description = "Target role (USER or MERCHANT).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Role role
) {}
