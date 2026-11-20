package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.settings.RuntimeSetting;
import com.relyon.economizaai.service.settings.RuntimeSettingReach;
import com.relyon.economizaai.service.settings.RuntimeSettings.SettingState;
import com.relyon.economizaai.service.settings.SettingGroup;
import com.relyon.economizaai.service.settings.SettingOptionLabels;
import com.relyon.economizaai.service.settings.SettingType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One business setting as the admin shows and edits it. {@code value} is what is in effect;
 * {@code configuredValue} is what the environment configures and what "restore" brings back.
 * {@code reflectsOn} says where a change shows up (screens, notices, routines).
 * {@code min}/{@code max} bound a number (for a text, {@code max} is its length); {@code options}
 * lists the accepted values of an option or option list and {@code optionLabels} how each one
 * reads (pt-BR) — send the value, show the label. {@code appKey} is set when the apps
 * read the value from {@code GET /app-config}. {@code group} is set when the setting is one field of a
 * rule spread over several: the admin draws the settings of a group as one block under its title and
 * explanation, in the order of {@code position}.
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
        Map<String, String> optionLabels,
        String appKey,
        LocalDateTime changedAt,
        String changedBy,
        Group group
) {
    /** The rule a setting is part of: how its fields combine, in plain words. */
    public record Group(String key, String title, String explanation, int position) {
        static Group of(RuntimeSetting setting) {
            var group = SettingGroup.of(setting);
            return group == null ? null
                    : new Group(group.name(), group.getTitle(), group.getExplanation(), group.getMembers().indexOf(setting));
        }
    }

    public static AdminSettingResponse from(SettingState state) {
        var setting = state.setting();
        var spec = setting.getSpec();
        return new AdminSettingResponse(setting.getKey(), setting.getLabel(), setting.getDescription(),
                RuntimeSettingReach.of(setting),
                spec.type(), spec.unit(), state.value(), state.configuredValue(), state.changed(),
                spec.min(), spec.max(), spec.options(), SettingOptionLabels.of(spec.options()), spec.appKey(), state.changedAt(), state.changedBy(),
                Group.of(setting));
    }
}
