package com.relyon.economizaai.service.sefaz;

import java.util.regex.Pattern;

public final class CpfMasker {

    private static final Pattern CPF_FORMATTED = Pattern.compile("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}");
    private static final Pattern CPF_DIGITS_ONLY = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");
    // Loosely-formatted variants some portals render ("123 456 789 00",
    // "123.456.789 00"), anchored to a nearby CPF label so ordinary
    // space-separated digit runs aren't swept.
    private static final Pattern CPF_LABELED_LOOSE = Pattern.compile(
            "(CPF\\s*:?\\s*)\\d{3}[ .]?\\d{3}[ .]?\\d{3}[ .-]?\\d{2}(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private CpfMasker() {}

    public static String strip(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        var labeledStripped = CPF_LABELED_LOOSE.matcher(input).replaceAll("$1***.***.***-**");
        var formattedStripped = CPF_FORMATTED.matcher(labeledStripped).replaceAll("***.***.***-**");
        return CPF_DIGITS_ONLY.matcher(formattedStripped).replaceAll("***********");
    }
}
