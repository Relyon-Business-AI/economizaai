package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaveReconsultServiceTest {

    private static final String RS_NFCE = "43250993015006000171651130003175331432171862";
    private static final String BA_NFCE = "29260816587844000110650010004061381036664035";

    @Test
    void isReconsultable_rsAndBaNfce_butNotOtherStates() {
        assertTrue(ChaveReconsultService.isReconsultable(RS_NFCE));
        assertTrue(ChaveReconsultService.isReconsultable(BA_NFCE));
        // SP (35) segue fora do import por chave pura
        assertFalse(ChaveReconsultService.isReconsultable(
                "35260816587844000110650010004061381036664035"));
        // BA modelo 55 (NF-e) não é servido pelo portal NFC-e
        assertFalse(ChaveReconsultService.isReconsultable(
                "29260816587844000110550010004061381036664035"));
    }

    @Test
    void bahiaClientHelpers_classifyResponses() throws Exception {
        var entry = new String(new ClassPathResource("fixtures/sefaz/ba/consulta-chave-entry.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var danfe = new String(new ClassPathResource("fixtures/sefaz/ba/nfce-real-consulta-chave.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(BahiaChaveConsultClient.looksLikeEntryForm(entry));
        assertFalse(BahiaChaveConsultClient.looksLikeDanfe(entry));
        assertTrue(BahiaChaveConsultClient.looksLikeDanfe(danfe));
        assertFalse(BahiaChaveConsultClient.looksLikeEntryForm(danfe));

        // extração dos campos ASP.NET do form real
        assertTrue(BahiaChaveConsultClient.extract(
                Pattern.compile("name=\"__VIEWSTATE\"[^>]*value=\"([^\"]*)\""), entry).length() > 100);
        assertNull(BahiaChaveConsultClient.extract(
                Pattern.compile("name=\"__NAO_EXISTE\"[^>]*value=\"([^\"]*)\""), entry));
    }
}
