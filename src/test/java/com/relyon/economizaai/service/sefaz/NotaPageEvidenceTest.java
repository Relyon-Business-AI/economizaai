package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotaPageEvidenceTest {

    private static final String CHAVE_PR_FIXTURE = "41260361585865261893650030000564031777660148";

    @Test
    void carriesChave_realPrDanfePrintsItsGroupedChave() throws Exception {
        var html = Files.readString(
                Path.of("src/test/resources/fixtures/sefaz/pr/nfce-real-raiadrogasil.html"), StandardCharsets.ISO_8859_1);

        assertTrue(NotaPageEvidence.carriesChave(html, CHAVE_PR_FIXTURE));
    }

    @Test
    void carriesChave_falseForAnotherNotasPage() throws Exception {
        var html = Files.readString(
                Path.of("src/test/resources/fixtures/sefaz/pr/nfce-real-raiadrogasil.html"), StandardCharsets.ISO_8859_1);

        assertFalse(NotaPageEvidence.carriesChave(html, "41261161585865228250650010002387131441261276"));
    }

    @Test
    void carriesChave_falseForPortalOutageStub() {
        assertFalse(NotaPageEvidence.carriesChave("Erro interno no servidor", CHAVE_PR_FIXTURE));
    }

    @Test
    void carriesChave_ignoresChaveEchoedOnlyInMarkupAttributes() {
        var html = "<html><body><a href=\"/nfce/qrcode?p=" + CHAVE_PR_FIXTURE + "\">Tentar novamente</a></body></html>";

        assertFalse(NotaPageEvidence.carriesChave(html, CHAVE_PR_FIXTURE));
    }

    @Test
    void carriesChave_falseForBlankInputs() {
        assertFalse(NotaPageEvidence.carriesChave(null, CHAVE_PR_FIXTURE));
        assertFalse(NotaPageEvidence.carriesChave("  ", CHAVE_PR_FIXTURE));
        assertFalse(NotaPageEvidence.carriesChave("<html></html>", null));
    }
}
