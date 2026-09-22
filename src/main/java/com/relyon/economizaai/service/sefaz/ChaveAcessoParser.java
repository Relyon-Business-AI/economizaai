package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;

import java.util.regex.Pattern;

public final class ChaveAcessoParser {

    private static final Pattern CHAVE_PATTERN = Pattern.compile("\\d{44}");
    private static final Pattern P_PARAM_PATTERN = Pattern.compile("[?&]p=([^&]+)", Pattern.CASE_INSENSITIVE);

    private ChaveAcessoParser() {}

    /**
     * True when the payload is a manually-typed bare chave (exactly 44 digits) —
     * NOT a scanned QR (full URL or a pipe payload that carries the signature).
     * Manual entry lacks the QR's signature params, so state portals that gate
     * the DANFE behind a signed QR (the SVRS family — RS/PR/SP) cannot be scraped
     * from it and must fall back (Infosimples) or fail; MS/SC consult by chave
     * natively (chNFe / ?p=chave), so a bare chave works there unchanged.
     */
    public static boolean isBareChave(String qrPayload) {
        return qrPayload != null && CHAVE_PATTERN.matcher(qrPayload.trim()).matches();
    }

    public static String extractChave(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) {
            throw new InvalidQrPayloadException();
        }
        var trimmed = qrPayload.trim();
        if (ScSecurityChallengeDetector.looksLikeUrl(trimmed)) {
            throw new CaptchaUnavailableException(UnidadeFederativa.SC.name());
        }

        var pMatcher = P_PARAM_PATTERN.matcher(trimmed);
        if (pMatcher.find()) {
            var pValue = pMatcher.group(1);
            var head = pValue.split("\\|", 2)[0];
            if (CHAVE_PATTERN.matcher(head).matches()) {
                return head;
            }
        }

        var pipeHead = trimmed.split("\\|", 2)[0];
        if (CHAVE_PATTERN.matcher(pipeHead).matches()) {
            return pipeHead;
        }

        if (CHAVE_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        throw new InvalidQrPayloadException();
    }

    public static UnidadeFederativa extractUf(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            throw new InvalidQrPayloadException();
        }
        var ufCode = Integer.parseInt(chave.substring(0, 2));
        try {
            return UnidadeFederativa.fromIbgeCode(ufCode);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQrPayloadException();
        }
    }

    /**
     * Mod-11 check digit (position 44) as defined by the NF-e layout: weights
     * 2..9 cycling from the rightmost of the first 43 digits; DV = 11 - (sum % 11),
     * with remainders 0 and 1 mapping to DV 0. Used to reject OCR misreads —
     * a single misread digit always breaks the DV.
     */
    public static boolean hasValidCheckDigit(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            return false;
        }
        var sum = 0;
        var weight = 2;
        for (var position = 42; position >= 0; position--) {
            sum += (chave.charAt(position) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        var remainder = sum % 11;
        var expectedDigit = remainder < 2 ? 0 : 11 - remainder;
        return chave.charAt(43) - '0' == expectedDigit;
    }

    public static String extractCnpj(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            throw new InvalidQrPayloadException();
        }
        return chave.substring(6, 20);
    }

    /**
     * The fiscal document model (positions 21-22 of the chave): "65" = NFC-e (the
     * grocery cupom), "55" = NF-e (e-commerce/B2B). Both encode the same 44-digit
     * layout, so a single import can carry a mix.
     */
    public static String extractModel(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            throw new InvalidQrPayloadException();
        }
        return chave.substring(20, 22);
    }

    /**
     * True when the note was NOT issued in normal mode (tpEmis, position 35 of
     * the chave, != 1) — e.g. 9 = NFC-e offline contingency. A contingency note
     * may take up to 24h to reach SEFAZ, so a portal rejection for it means
     * "not available yet", not "broken receipt".
     */
    public static boolean isContingencyEmission(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            return false;
        }
        return chave.charAt(34) != '1';
    }
}
