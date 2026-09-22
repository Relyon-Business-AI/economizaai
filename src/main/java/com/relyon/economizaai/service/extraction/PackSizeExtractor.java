package com.relyon.economizaai.service.extraction;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public final class PackSizeExtractor {

    private static final Pattern WEIGHT_VOLUME = Pattern.compile(
            "(\\d++(?:[.,]\\d++)?)\\s*+(KG|G|MG|L|ML|CL)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PACK_COUNT = Pattern.compile(
            "(?:C\\s*+[/.]|CX\\s*+[/.]|PCT\\s*+[/.]|COM\\s++)\\s*+(\\d++)",
            Pattern.CASE_INSENSITIVE);

    private PackSizeExtractor() {}

    public static PackSize extract(String text) {
        if (text == null || text.isBlank()) return PackSize.EMPTY;
        var weightMatch = WEIGHT_VOLUME.matcher(text);
        if (weightMatch.find()) {
            var size = new BigDecimal(weightMatch.group(1).replace(',', '.'));
            var unit = weightMatch.group(2).toUpperCase();
            return new PackSize(size, unit);
        }
        var packMatch = PACK_COUNT.matcher(text);
        if (packMatch.find()) {
            return new PackSize(new BigDecimal(packMatch.group(1)), "UN");
        }
        return PackSize.EMPTY;
    }

    public record PackSize(BigDecimal size, String unit) {
        public static final PackSize EMPTY = new PackSize(null, null);
    }
}
