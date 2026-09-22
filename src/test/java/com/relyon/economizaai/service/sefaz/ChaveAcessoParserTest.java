package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaveAcessoParserTest {

    private static final String CHAVE_RS = "43250912345678000190650010000123451123456780";

    @Test
    void extractChave_acceptsRawChave() {
        assertEquals(CHAVE_RS, ChaveAcessoParser.extractChave(CHAVE_RS));
    }

    @Test
    void isBareChave_trueForExactly44Digits() {
        assertTrue(ChaveAcessoParser.isBareChave(CHAVE_RS));
        assertTrue(ChaveAcessoParser.isBareChave("  " + CHAVE_RS + "  "), "trimmed");
    }

    @Test
    void isBareChave_falseForScannedQrPayloads() {
        assertFalse(ChaveAcessoParser.isBareChave(CHAVE_RS + "|2|1|1|abcdef"), "pipe payload carries the signature");
        assertFalse(ChaveAcessoParser.isBareChave(
                "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|2|1"), "full URL");
    }

    @Test
    void isBareChave_falseForNullOrNon44Digits() {
        assertFalse(ChaveAcessoParser.isBareChave(null));
        assertFalse(ChaveAcessoParser.isBareChave("1234567890"));
        assertFalse(ChaveAcessoParser.isBareChave(CHAVE_RS + "1"), "45 digits");
    }

    @Test
    void extractChave_acceptsPipeSeparatedPayload() {
        var payload = CHAVE_RS + "|2|1|1|abcdef0123456789";
        assertEquals(CHAVE_RS, ChaveAcessoParser.extractChave(payload));
    }

    @Test
    void extractChave_acceptsFullSefazUrl() {
        var url = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|2|1|1|abcdef";
        assertEquals(CHAVE_RS, ChaveAcessoParser.extractChave(url));
    }

    @Test
    void extractChave_rejectsBlank() {
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractChave(" "));
    }

    @Test
    void extractChave_rejectsNonNumeric() {
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractChave("not-a-chave"));
    }

    @Test
    void extractChave_reportsScSecurityVerifyAsCaptchaUnavailable() {
        var url = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=encrypted-token";
        assertThrows(CaptchaUnavailableException.class, () -> ChaveAcessoParser.extractChave(url));
    }

    @Test
    void extractChave_rejectsTooShort() {
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractChave("1234567890"));
    }

    @Test
    void extractUf_returnsRsForCode43() {
        assertEquals(UnidadeFederativa.RS, ChaveAcessoParser.extractUf(CHAVE_RS));
    }

    @Test
    void extractUf_returnsSpForCode35() {
        var spChave = "35" + CHAVE_RS.substring(2);
        assertEquals(UnidadeFederativa.SP, ChaveAcessoParser.extractUf(spChave));
    }

    @Test
    void extractUf_rejectsUnknownCode() {
        var bogus = "99" + CHAVE_RS.substring(2);
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractUf(bogus));
    }

    @Test
    void extractCnpj_returnsCnpjFromChave() {
        assertEquals("12345678000190", ChaveAcessoParser.extractCnpj(CHAVE_RS));
    }

    // DV precomputed with the NF-e mod-11 algorithm for this 43-digit prefix.
    private static final String CHAVE_VALID_DV = "43260412345678000190650010000123451123456782";

    @Test
    void hasValidCheckDigit_trueForCorrectDv() {
        assertTrue(ChaveAcessoParser.hasValidCheckDigit(CHAVE_VALID_DV));
    }

    @Test
    void hasValidCheckDigit_falseForWrongDv() {
        var prefix = CHAVE_VALID_DV.substring(0, 43);
        for (var digit = 0; digit <= 9; digit++) {
            if (digit == CHAVE_VALID_DV.charAt(43) - '0') {
                continue;
            }
            assertFalse(ChaveAcessoParser.hasValidCheckDigit(prefix + digit), "dv=" + digit);
        }
    }

    @Test
    void hasValidCheckDigit_falseWhenAnyDigitMisread() {
        // Single-digit OCR misreads must always break the DV.
        var chars = CHAVE_VALID_DV.toCharArray();
        var position = 10;
        chars[position] = chars[position] == '9' ? '0' : (char) (chars[position] + 1);
        assertFalse(ChaveAcessoParser.hasValidCheckDigit(new String(chars)));
    }

    @Test
    void hasValidCheckDigit_falseForNullOrMalformed() {
        assertFalse(ChaveAcessoParser.hasValidCheckDigit(null));
        assertFalse(ChaveAcessoParser.hasValidCheckDigit("123"));
        assertFalse(ChaveAcessoParser.hasValidCheckDigit(CHAVE_VALID_DV + "1"));
    }

    @Test
    void isContingencyEmission_trueForOfflineContingency() {
        // Real bar receipt printed "EMITIDA EM CONTINGÊNCIA": tpEmis (position 35) = 9
        assertTrue(ChaveAcessoParser.isContingencyEmission("43260735254422000178650090001371069852732246"));
    }

    @Test
    void isContingencyEmission_falseForNormalEmission() {
        // CHAVE_RS has tpEmis = 1 (normal)
        assertFalse(ChaveAcessoParser.isContingencyEmission(CHAVE_RS));
    }

    @Test
    void isContingencyEmission_falseForNullOrMalformed() {
        assertFalse(ChaveAcessoParser.isContingencyEmission(null));
        assertFalse(ChaveAcessoParser.isContingencyEmission("123"));
    }
}
