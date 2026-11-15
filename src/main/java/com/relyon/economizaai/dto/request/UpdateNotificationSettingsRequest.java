package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Partial update of the notification settings: every field is optional and only what is
 * sent changes. Keys, channels and the frequencies travel as text so an unknown value gets a
 * clear, localized 400 instead of a generic parse error.
 */
public record UpdateNotificationSettingsRequest(
        @Schema(description = "Options to switch on/off. Options not listed are left as they are.")
        List<OptionChange> options,
        @Schema(description = "Deals digest frequency.", allowableValues = {"DAILY", "WEEKLY"}, example = "WEEKLY")
        String digestFrequency,
        @Schema(description = "Periods the spending report covers; replaces the selection. At least one.",
                allowableValues = {"WEEKLY", "MONTHLY"}, example = "[\"WEEKLY\", \"MONTHLY\"]")
        List<String> reportFrequencies
) {

    public record OptionChange(
            @Schema(description = "Notice key.", example = "PRICE_ALERTS")
            String key,
            @Schema(description = "A channel that exists for the notice.", example = "EMAIL")
            String channel,
            @Schema(example = "true")
            Boolean enabled
    ) {}
}
