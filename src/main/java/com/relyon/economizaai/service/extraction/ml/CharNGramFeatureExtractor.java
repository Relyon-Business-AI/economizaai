package com.relyon.economizaai.service.extraction.ml;

import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a raw product description into a list of character n-grams (3 to 5).
 * Char n-grams handle the noisy/abbreviated nature of NFC-e descriptions
 * better than word tokens — "ARROZ TIO J" and "ARROZ TIO JOAO" share most
 * 3-grams ("arr", "rro", "roz", "oz ", " ti", "tio") which gives the
 * classifier robustness against abbreviations and typos.
 */
public final class CharNGramFeatureExtractor {

    private static final int MIN_N = 3;
    private static final int MAX_N = 5;

    private CharNGramFeatureExtractor() {}

    public static List<String> extract(String text) {
        if (text == null) return List.of();
        var normalized = DescriptionNormalizer.normalize(text);
        if (normalized.isBlank()) return List.of();
        var grams = new ArrayList<String>();
        for (var n = MIN_N; n <= MAX_N; n++) {
            for (var i = 0; i + n <= normalized.length(); i++) {
                grams.add(normalized.substring(i, i + n));
            }
        }
        return grams;
    }
}
