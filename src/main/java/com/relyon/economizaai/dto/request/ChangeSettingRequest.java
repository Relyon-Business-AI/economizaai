package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The value typed in the admin, as text; an empty text is a valid value for the settings that accept "none". */
public record ChangeSettingRequest(
        @NotNull @Size(max = 2000) String value
) {}
