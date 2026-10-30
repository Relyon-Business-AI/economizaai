package com.relyon.economizaai.service.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import com.relyon.economizaai.exception.OcrUnavailableException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChaveAcessoOcrServiceTest {

    private static final String CHAVE_VALID_DV = "43260412345678000190650010000123451123456782";

    @Mock
    private OcrEngine ocrEngine;

    private ChaveAcessoOcrService service;

    @BeforeEach
    void setUp() {
        var validator = new PhotoUploadValidator(5);
        service = new ChaveAcessoOcrService(validator, ocrEngine, new QrCodePhotoDecoder(validator));
    }

    @Test
    void qrCodeInPhoto_returnsSignedPayload_withoutTouchingOcr() throws Exception {
        var payload = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_VALID_DV + "|2|1|1|ABCDEF";
        var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 400, 400);
        var photo = new MockMultipartFile("file", "qr.png", "image/png",
                PhotoUploadValidatorTest.pngBytes(MatrixToImageWriter.toBufferedImage(matrix)));

        var extracted = service.extractChave(photo);

        assertEquals(payload, extracted.qrPayload());
        assertEquals(CHAVE_VALID_DV, extracted.chaveAcesso());
        assertEquals(UnidadeFederativa.RS, extracted.uf());
        // nem consultou o OCR — o caminho assinado tem prioridade (funciona em qualquer estado)
    }

    private static MockMultipartFile anyPhoto() throws IOException {
        var bytes = PhotoUploadValidatorTest.pngBytes(new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB));
        return new MockMultipartFile("file", "chave.png", "image/png", bytes);
    }

    @Test
    void extractChave_findsChaveInCleanOcrText() throws IOException {
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(CHAVE_VALID_DV);

        var response = service.extractChave(anyPhoto());

        assertEquals(CHAVE_VALID_DV, response.chaveAcesso());
        assertEquals(UnidadeFederativa.RS, response.uf());
    }

    @Test
    void extractChave_findsChavePrintedInGroupsOfFourAcrossTwoLines() throws IOException {
        var printedLayout = "4326 0412 3456 7800 0190 6500 1000\n0123 4511 2345 6782";
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(printedLayout);

        assertEquals(CHAVE_VALID_DV, service.extractChave(anyPhoto()).chaveAcesso());
    }

    @Test
    void extractChave_ignoresSurroundingDigitNoiseFromReceiptBody() throws IOException {
        // Totals and CNPJ lines around the chave must not shift the window
        // onto a wrong 44-digit run — the DV+UF gate skips them.
        var noisyText = "57 80 12345678000190\n" + CHAVE_VALID_DV + "\n0800 123 4567";
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(noisyText);

        assertEquals(CHAVE_VALID_DV, service.extractChave(anyPhoto()).chaveAcesso());
    }

    @Test
    void extractChave_returnsBestEffortGuessWhenCheckDigitFailsButStructureValid() throws IOException {
        // UF + modelo + AAMM válidos, mas o DV não fecha (1 dígito trocado) — provável
        // erro de OCR. Volta como melhor-esforço (uncertain=true) pro usuário conferir.
        var dvBroken = CHAVE_VALID_DV.substring(0, 43) + (CHAVE_VALID_DV.charAt(43) == '2' ? "3" : "2");
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(dvBroken);

        var response = service.extractChave(anyPhoto());

        assertEquals(dvBroken, response.chaveAcesso());
        assertEquals(UnidadeFederativa.RS, response.uf());
        assertEquals(true, response.uncertain());
    }

    @Test
    void extractChave_votingAcrossPassesRecoversConfidentRead() throws IOException {
        // Cada passe erra 1 dígito DIFERENTE (cada um falha o DV sozinho); o voto por
        // posição recupera a chave correta → DV fecha → leitura confiável, sem incerteza.
        var pass1 = flipDigit(CHAVE_VALID_DV, 10);
        var pass2 = flipDigit(CHAVE_VALID_DV, 25);
        var pass3 = flipDigit(CHAVE_VALID_DV, 38);
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(pass1, pass2, pass3);

        var response = service.extractChave(anyPhoto());

        assertEquals(CHAVE_VALID_DV, response.chaveAcesso());
        assertEquals(false, response.uncertain());
        assertEquals(0, response.uncertainPositions().size());
    }

    @Test
    void extractChave_flagsDisagreementPositionWhenVoteStillFailsDv() throws IOException {
        // Dois passes leem o MESMO dígito errado na posição 10 (vencem o voto) e um lê
        // certo. O voto mantém o erro → DV não fecha → uncertain + posição 10 marcada.
        var wrongAt10 = flipDigit(CHAVE_VALID_DV, 10);
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(wrongAt10, wrongAt10, CHAVE_VALID_DV);

        var response = service.extractChave(anyPhoto());

        assertEquals(true, response.uncertain());
        assertEquals(List.of(10), response.uncertainPositions());
    }

    private static String flipDigit(String chave, int position) {
        var replacement = (char) ('0' + ((chave.charAt(position) - '0' + 1) % 10));
        return chave.substring(0, position) + replacement + chave.substring(position + 1);
    }

    @Test
    void extractChave_rejectsWhenNoWindowPassesCheckDigit() throws IOException {
        var misread = CHAVE_VALID_DV.substring(0, 20) + "0" + CHAVE_VALID_DV.substring(21);
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(misread);

        var photo = anyPhoto();
        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> service.extractChave(photo));
        assertEquals("receipt.photo.chave.unreadable", exception.getMessageKey());
    }

    @Test
    void extractChave_fallsBackToHardPreprocessWhenLightPassMisses() throws IOException {
        // Passe leve não acha chave válida (lixo sem janela que case DV+UF+modelo);
        // o passe forte (preprocessHard: upscale+unsharp+binarização) roda em seguida
        // e lê a chave. Garante que a foto degradada é resgatada, não rejeitada.
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class)))
                .thenReturn("0000 1111 2222 3333", CHAVE_VALID_DV);

        var response = service.extractChave(anyPhoto());

        assertEquals(CHAVE_VALID_DV, response.chaveAcesso());
        assertEquals(UnidadeFederativa.RS, response.uf());
        verify(ocrEngine, times(2)).recognizeDigits(any(BufferedImage.class));
    }

    @Test
    void extractChave_skipsHardPassWhenLightPassAlreadyFindsChave() throws IOException {
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(CHAVE_VALID_DV);

        service.extractChave(anyPhoto());

        // passe leve bastou — o forte (mais caro) não é acionado
        verify(ocrEngine, times(1)).recognizeDigits(any(BufferedImage.class));
    }

    @Test
    void extractChave_rejectsFalsePositiveWindowWithInvalidModel() throws IOException {
        // Regressão real (nota RS amassada, 2026): o OCR devolveu uma janela de 44
        // dígitos que CASOU o DV e tinha UF válida (12 = AC), mas com modelo 52 —
        // inexistente (NFC-e=65, NF-e=55). Sem o gate de modelo isso voltava como
        // chave "válida" e o app preenchia 44/44 ✓ com lixo.
        var falsePositive = "12000546334172860617522432619999925942008202";
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(falsePositive);

        var photo = anyPhoto();
        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> service.extractChave(photo));
        assertEquals("receipt.photo.chave.unreadable", exception.getMessageKey());
    }

    @Test
    void extractChave_rejectsWhenOcrReturnsTooFewDigits() throws IOException {
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn("1234 5678");

        var photo = anyPhoto();
        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> service.extractChave(photo));
        assertEquals("receipt.photo.chave.unreadable", exception.getMessageKey());
    }

    @Test
    void extractChave_failsFastWhenEngineUnavailable() throws IOException {
        when(ocrEngine.isAvailable()).thenReturn(false);

        var photo = anyPhoto();
        assertThrows(OcrUnavailableException.class, () -> service.extractChave(photo));
    }
}
