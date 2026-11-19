package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.exception.InvalidSettingValueException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Turns what was typed in the admin into the setting's canonical text (the form stored and
 * handed to the code that uses it), refusing anything the setting does not accept.
 */
public final class SettingValueCodec {

    private static final String LIST_SEPARATOR = ",";

    private SettingValueCodec() {}

    public static String normalize(RuntimeSetting setting, String rawValue) {
        var typed = rawValue == null ? "" : rawValue.trim();
        var spec = setting.getSpec();
        return switch (spec.type()) {
            case INTEGER -> normalizeInteger(setting, typed);
            case DECIMAL -> normalizeDecimal(setting, typed);
            case BOOLEAN -> normalizeBoolean(setting, typed);
            case TEXT -> normalizeText(setting, typed);
            case DATE -> normalizeDate(setting, typed);
            case INTEGER_LIST -> normalizeIntegerList(setting, typed);
            case UF_LIST -> normalizeUfList(setting, typed);
            case OPTION -> normalizeOption(setting, typed);
            case OPTION_LIST -> normalizeOptionList(setting, typed);
        };
    }

    /** The JSON-friendly value of a canonical text: a number, a boolean, a list or the text itself. */
    public static Object toJsonValue(RuntimeSetting setting, String canonicalValue) {
        return switch (setting.getSpec().type()) {
            case INTEGER -> Long.valueOf(canonicalValue);
            case DECIMAL -> new BigDecimal(canonicalValue);
            case BOOLEAN -> Boolean.valueOf(canonicalValue);
            case INTEGER_LIST -> splitList(canonicalValue).stream().map(Long::valueOf).toList();
            case UF_LIST, OPTION_LIST -> splitList(canonicalValue);
            case TEXT, DATE, OPTION -> canonicalValue.isEmpty() ? null : canonicalValue;
        };
    }

    public static List<String> splitList(String canonicalValue) {
        if (canonicalValue == null || canonicalValue.isBlank()) return List.of();
        return Arrays.stream(canonicalValue.split(LIST_SEPARATOR))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private static String normalizeInteger(RuntimeSetting setting, String typed) {
        try {
            var number = new BigDecimal(typed).toBigIntegerExact();
            requireInRange(setting, new BigDecimal(number));
            return number.toString();
        } catch (NumberFormatException | ArithmeticException notAWholeNumber) {
            throw new InvalidSettingValueException("admin.settings.value.not-integer", setting.getLabel());
        }
    }

    private static String normalizeDecimal(RuntimeSetting setting, String typed) {
        try {
            var number = new BigDecimal(typed.replace(',', '.'));
            requireInRange(setting, number);
            return number.toPlainString();
        } catch (NumberFormatException notANumber) {
            throw new InvalidSettingValueException("admin.settings.value.not-number", setting.getLabel());
        }
    }

    private static String normalizeBoolean(RuntimeSetting setting, String typed) {
        if ("true".equalsIgnoreCase(typed)) return "true";
        if ("false".equalsIgnoreCase(typed)) return "false";
        throw new InvalidSettingValueException("admin.settings.value.not-boolean", setting.getLabel());
    }

    private static String normalizeText(RuntimeSetting setting, String typed) {
        var maxLength = setting.getSpec().max().intValue();
        if (typed.length() > maxLength) {
            throw new InvalidSettingValueException("admin.settings.value.too-long",
                    setting.getLabel(), String.valueOf(maxLength));
        }
        return typed;
    }

    private static String normalizeDate(RuntimeSetting setting, String typed) {
        if (typed.isEmpty()) return "";
        try {
            return LocalDate.parse(typed).toString();
        } catch (DateTimeParseException notADate) {
            throw new InvalidSettingValueException("admin.settings.value.not-date", setting.getLabel());
        }
    }

    private static String normalizeIntegerList(RuntimeSetting setting, String typed) {
        var entries = splitList(typed);
        if (entries.isEmpty()) {
            throw new InvalidSettingValueException("admin.settings.value.empty-list", setting.getLabel());
        }
        return entries.stream()
                .map(entry -> normalizeInteger(setting, entry))
                .collect(Collectors.joining(LIST_SEPARATOR));
    }

    private static String normalizeUfList(RuntimeSetting setting, String typed) {
        var codes = new LinkedHashSet<String>();
        for (var entry : splitList(typed)) {
            var code = entry.toUpperCase(Locale.ROOT);
            if (Arrays.stream(UnidadeFederativa.values()).noneMatch(uf -> uf.name().equals(code))) {
                throw new InvalidSettingValueException("admin.settings.value.unknown-uf", setting.getLabel(), entry);
            }
            codes.add(code);
        }
        return String.join(LIST_SEPARATOR, codes);
    }

    private static String normalizeOption(RuntimeSetting setting, String typed) {
        return matchingOption(setting, typed);
    }

    private static String normalizeOptionList(RuntimeSetting setting, String typed) {
        var chosen = new LinkedHashSet<String>();
        for (var entry : splitList(typed)) {
            chosen.add(matchingOption(setting, entry));
        }
        return String.join(LIST_SEPARATOR, chosen);
    }

    private static String matchingOption(RuntimeSetting setting, String typed) {
        return setting.getSpec().options().stream()
                .filter(option -> option.equalsIgnoreCase(typed))
                .findFirst()
                .orElseThrow(() -> new InvalidSettingValueException("admin.settings.value.unknown-option",
                        setting.getLabel(), typed));
    }

    private static void requireInRange(RuntimeSetting setting, BigDecimal number) {
        var spec = setting.getSpec();
        if (number.compareTo(spec.min()) < 0 || number.compareTo(spec.max()) > 0) {
            throw new InvalidSettingValueException("admin.settings.value.out-of-range",
                    setting.getLabel(), spec.min().toPlainString(), spec.max().toPlainString());
        }
    }
}
