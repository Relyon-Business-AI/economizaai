package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.settings.RuntimeSettingReach;
import com.relyon.economizaai.service.settings.RuntimeSettings.SettingState;
import com.relyon.economizaai.service.settings.SettingType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One business setting as the admin shows and edits it. {@code value} is what is in effect;
 * {@code configuredValue} is what the environment configures and what "restore" brings back.
 * {@code reflectsOn} says where a change shows up (screens, notices, routines).
 * {@code min}/{@code max} bound a number (for a text, {@code max} is its length); {@code options}
 * lists the accepted values of an option or option list. {@code appKey} is set when the apps
 * read the value from {@code GET /app-config}.
 */
public record AdminSettingResponse(
        String key,
        String label,
        String description,
        String reflectsOn,
        SettingType type,
        String unit,
        String value,
        String configuredValue,
        boolean changed,
        BigDecimal min,
        BigDecimal max,
        List<String> options,
        String appKey,
        LocalDateTime changedAt,
        String changedBy
) {
    public static AdminSettingResponse from(SettingState state) {
        var setting = state.setting();
        var spec = setting.getSpec();
        return new AdminSettingResponse(setting.getKey(), setting.getLabel(), setting.getDescription(),
                RuntimeSettingReach.of(setting),
                spec.type(), spec.unit(), state.value(), state.configuredValue(), state.changed(),
                spec.min(), spec.max(), spec.options(), spec.appKey(), state.changedAt(), state.changedBy());
    }
}
