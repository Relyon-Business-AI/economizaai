package com.relyon.economizaai.service.sefaz;

import java.util.regex.Pattern;

public final class CpfMasker {

    private static final Pattern CPF_FORMATTED = Pattern.compile("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}");
    private static final Pattern CPF_DIGITS_ONLY = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");

    private CpfMasker() {}

    public static String strip(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        var formattedStripped = CPF_FORMATTED.matcher(input).replaceAll("***.***.***-**");
        return CPF_DIGITS_ONLY.matcher(formattedStripped).replaceAll("***********");
    }
}
