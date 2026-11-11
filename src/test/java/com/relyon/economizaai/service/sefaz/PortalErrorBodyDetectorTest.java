package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalErrorBodyDetectorTest {

    @Test
    void looksLikeServerErrorStub_detectsPrOutageBody() {
        assertTrue(PortalErrorBodyDetector.looksLikeServerErrorStub("Erro interno no servidor"));
    }

    @Test
    void looksLikeServerErrorStub_detectsCommonGatewayStubs() {
        assertTrue(PortalErrorBodyDetector.looksLikeServerErrorStub("<h1>503 Service Unavailable</h1>"));
        assertTrue(PortalErrorBodyDetector.looksLikeServerErrorStub("Serviço indisponível"));
    }

    @Test
    void looksLikeServerErrorStub_falseForRealDanfe() throws Exception {
        var html = Files.readString(
                Path.of("src/test/resources/fixtures/sefaz/pr/nfce-real-raiadrogasil.html"), StandardCharsets.ISO_8859_1);

        assertFalse(PortalErrorBodyDetector.looksLikeServerErrorStub(html));
    }

    @Test
    void looksLikeServerErrorStub_falseForShortUnrelatedOrBlankBody() {
        assertFalse(PortalErrorBodyDetector.looksLikeServerErrorStub("<html>ok</html>"));
        assertFalse(PortalErrorBodyDetector.looksLikeServerErrorStub(""));
        assertFalse(PortalErrorBodyDetector.looksLikeServerErrorStub(null));
    }
}
