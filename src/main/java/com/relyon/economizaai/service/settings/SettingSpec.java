package com.relyon.economizaai.service.settings;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a setting accepts: its type, the allowed range or options, the unit shown next to the
 * field and, when the mobile/web app also needs the value, the name it is published under in
 * {@code GET /app-config}. A text setting may also carry the {@link TextFormat} it must follow.
 */
public record SettingSpec(SettingType type, BigDecimal min, BigDecimal max, String unit,
                          List<String> options, String appKey, TextFormat format) {

    public SettingSpec(SettingType type, BigDecimal min, BigDecimal max, String unit,
                       List<String> options, String appKey) {
        this(type, min, max, unit, options, appKey, null);
    }

    public static SettingSpec integer(long min, long max) {
        return new SettingSpec(SettingType.INTEGER, BigDecimal.valueOf(min), BigDecimal.valueOf(max), null, List.of(), null);
    }

    public static SettingSpec decimal(String min, String max) {
        return new SettingSpec(SettingType.DECIMAL, new BigDecimal(min), new BigDecimal(max), null, List.of(), null);
    }

    public static SettingSpec toggle() {
        return of(SettingType.BOOLEAN);
    }

    public static SettingSpec text(int maxLength) {
        return new SettingSpec(SettingType.TEXT, null, BigDecimal.valueOf(maxLength), null, List.of(), null);
    }

    public static SettingSpec date() {
        return of(SettingType.DATE);
    }

    /** Whole numbers, each inside {@code [min, max]}. */
    public static SettingSpec integerList(long min, long max) {
        return new SettingSpec(SettingType.INTEGER_LIST, BigDecimal.valueOf(min), BigDecimal.valueOf(max), null, List.of(), null);
    }

    public static SettingSpec ufList() {
        return of(SettingType.UF_LIST);
    }

    public static SettingSpec option(List<String> options) {
        return new SettingSpec(SettingType.OPTION, null, null, null, List.copyOf(options), null);
    }

    public static SettingSpec optionList(List<String> options) {
        return new SettingSpec(SettingType.OPTION_LIST, null, null, null, List.copyOf(options), null);
    }

    /** A text of at most {@code maxLength} characters that must also follow a format. */
    public static SettingSpec formattedText(int maxLength, TextFormat format) {
        return new SettingSpec(SettingType.TEXT, null, BigDecimal.valueOf(maxLength), null, List.of(), null, format);
    }

    public SettingSpec unit(String unitLabel) {
        return new SettingSpec(type, min, max, unitLabel, options, appKey, format);
    }

    /** Publishes the value to the apps under this name. */
    public SettingSpec app(String publishedName) {
        return new SettingSpec(type, min, max, unit, options, publishedName, format);
    }

    private static SettingSpec of(SettingType type) {
        return new SettingSpec(type, null, null, null, List.of(), null);
    }
}
