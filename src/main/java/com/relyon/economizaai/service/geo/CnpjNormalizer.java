package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.exception.InvalidCnpjException;

/**
 * Strips CNPJ formatting (dots/slash/dash) and requires exactly 14 digits.
 * Rejects malformed input with a 400 ({@link InvalidCnpjException}) before it
 * can fall through to a misleading 404 or overflow the 14-char {@code cnpj}
 * columns. Shared by the market-name and watched-market flows so both validate
 * CNPJs identically.
 */
public final class CnpjNormalizer {

    private CnpjNormalizer() {}

    public static String normalize(String cnpj) {
        var digits = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (digits.length() != 14) {
            throw new InvalidCnpjException("market.cnpj.invalid");
        }
        return digits;
    }
}
