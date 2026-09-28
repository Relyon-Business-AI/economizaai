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
        // Scan ALL matches and keep the LAST one. Pharmaceutical descriptions often put the
        // dosage/concentration first and the actual container volume second
        // (e.g. "PULMICORT 0,50MG CX 5 FR X 2ML" — 0,50MG is dosage, 2ML is the real pack size).
        // For grocery items, there is normally only one measurement so first == last.
        BigDecimal lastSize = null;
        String lastUnit = null;
        while (weightMatch.find()) {
            lastSize = new BigDecimal(weightMatch.group(1).replace(',', '.'));
            lastUnit = weightMatch.group(2).toUpperCase();
        }
        if (lastSize != null) {
            return new PackSize(lastSize, lastUnit);
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
