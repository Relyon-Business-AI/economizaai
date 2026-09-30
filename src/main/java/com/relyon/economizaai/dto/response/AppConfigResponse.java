package com.relyon.economizaai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Remote config consumed by the mobile ForceUpdateGate ({@code appConfigService.ts}).
 * Field names mirror the legacy gist JSON exactly ({@code AppRemoteConfig}) so the
 * FE only swaps the URL: {@code minVersion} is required (string), the rest are
 * optional — unset values are omitted so the FE's {@code ??} fallbacks apply.
 */
@JsonInclude(Include.NON_NULL)
public record AppConfigResponse(
        String minVersion,
        String androidUrl,
        String iosUrl,
        String message
) {}
