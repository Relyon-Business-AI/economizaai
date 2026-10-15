package com.relyon.economizaai.dto.response;

import java.util.UUID;

/**
 * A category available to a household: either a global enum ({@code id=null,
 * custom=false}) or a household-defined custom category ({@code custom=true}).
 * Custom categories may carry a visual (icon/color) and nest under a taxonomy
 * group ({@code parentSlug}/{@code parentName}).
 */
public record CategoryResponse(UUID id, String name, boolean custom,
                               String icon, String color, String parentSlug, String parentName) {

    /** Global enum category — no custom visual/nesting. */
    public static CategoryResponse enumCategory(String name) {
        return new CategoryResponse(null, name, false, null, null, null, null);
    }

    /** Custom category without visual/nesting (back-compat convenience). */
    public static CategoryResponse custom(UUID id, String name) {
        return new CategoryResponse(id, name, true, null, null, null, null);
    }
}
