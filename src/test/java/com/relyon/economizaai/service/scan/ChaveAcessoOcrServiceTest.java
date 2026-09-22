package com.relyon.economizaai.service.scan;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChaveAcessoOcrServiceTest {

    private static final String CHAVE_VALID_DV = "43260412345678000190650010000123451123456782";

    @Mock
    private OcrEngine ocrEngine;

    private ChaveAcessoOcrService service;

    @BeforeEach
    void setUp() {
        service = new ChaveAcessoOcrService(new PhotoUploadValidator(5), ocrEngine);
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
    void extractChave_rejectsWhenNoWindowPassesCheckDigit() throws IOException {
        var misread = CHAVE_VALID_DV.substring(0, 20) + "0" + CHAVE_VALID_DV.substring(21);
        when(ocrEngine.isAvailable()).thenReturn(true);
        when(ocrEngine.recognizeDigits(any(BufferedImage.class))).thenReturn(misread);

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
