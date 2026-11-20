package com.relyon.economizaai.dto.response;

import java.util.List;

/** A group of settings as the admin lists them. */
public record AdminSettingSectionResponse(
        String key,
        String label,
        String description,
        List<AdminSettingResponse> settings
) {}
