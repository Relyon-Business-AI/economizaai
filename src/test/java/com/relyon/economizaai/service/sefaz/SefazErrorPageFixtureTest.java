package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazPortalRejectionException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real SVRS rejection page (captured 2026-07-16): a bar's NFC-e issued in
 * OFFLINE CONTINGENCY (tpEmis=9) whose QR consult returns
 * "227 - DigestValue informado no QR Code inconsistente..." instead of the
 * DANFE. The parser must classify it honestly instead of "no-items-found".
 */
class SefazErrorPageFixtureTest {

    // Real contingency chave from the captured receipt: tpEmis (position 35) = 9.
    private static final String CONTINGENCY_CHAVE = "43260735254422000178650090001371069852732246";
    // Same chave with tpEmis flipped to 1 (normal emission) — DV not revalidated by the parser.
    private static final String NORMAL_CHAVE = "43260735254422000178650090001371061852732246";

    private String errorPageHtml() throws Exception {
        return new String(new ClassPathResource("fixtures/sefaz/rs/qrcode-error-227-digest-mismatch.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void contingencyChave_errorPage_classifiesAsContingencyPending() throws Exception {
        var exception = assertThrows(SefazPortalRejectionException.class,
                () -> ResponsiveDanfeParser.parse(errorPageHtml(), CONTINGENCY_CHAVE, "https://test/source"));

        assertEquals("receipt.contingency.pending", exception.getMessageKey());
        assertEquals("227", exception.getArguments()[0]);
    }

    @Test
    void normalChave_errorPage_classifiesAsSefazRejectedQr() throws Exception {
        var exception = assertThrows(SefazPortalRejectionException.class,
                () -> ResponsiveDanfeParser.parse(errorPageHtml(), NORMAL_CHAVE, "https://test/source"));

        assertEquals("receipt.sefaz.rejected_qr", exception.getMessageKey());
        assertEquals("227", exception.getArguments()[0]);
    }

    @Test
    void emptyPageWithoutAlert_stillFallsBackToNoItemsFound() {
        var exception = assertThrows(ReceiptParseException.class,
                () -> ResponsiveDanfeParser.parse("<html><body></body></html>", CONTINGENCY_CHAVE, "https://test/source"));

        assertEquals("receipt.parse.failed", exception.getMessageKey());
        assertEquals("no-items-found", exception.getArguments()[0]);
    }
}
