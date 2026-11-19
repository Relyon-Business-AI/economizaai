package com.relyon.economizaai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.Map;

/**
 * Remote config consumed by the apps ({@code appConfigService.ts}). {@code minVersion} is
 * required (string); the other force-update fields are optional — unset values are omitted so
 * the FE's {@code ??} fallbacks apply. {@code settings} carries the business values the apps
 * show or apply (price window, "perto de você" radius, photos per nota...), by published name.
 */
@JsonInclude(Include.NON_NULL)
public record AppConfigResponse(
        String minVersion,
        String androidUrl,
        String iosUrl,
        String message,
        Map<String, Object> settings
) {}
