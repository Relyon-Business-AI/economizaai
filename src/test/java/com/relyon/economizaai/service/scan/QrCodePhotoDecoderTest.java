package com.relyon.economizaai.service.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QrCodePhotoDecoderTest {

    private static final String QR_PAYLOAD =
            "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=43260412345678000190650010000123451123456782|2|1|1|abcdef0123456789";

    private final QrCodePhotoDecoder decoder = new QrCodePhotoDecoder(new PhotoUploadValidator(5));

    private static MockMultipartFile qrPhoto(String payload) throws WriterException, IOException {
        var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 400, 400);
        var image = MatrixToImageWriter.toBufferedImage(matrix);
        return new MockMultipartFile("file", "qr.png", "image/png", PhotoUploadValidatorTest.pngBytes(image));
    }

    @Test
    void decode_extractsPayloadFromQrPhoto() throws Exception {
        assertEquals(QR_PAYLOAD, decoder.decode(qrPhoto(QR_PAYLOAD)));
    }

    @Test
    void decode_extractsBareChavePayload() throws Exception {
        var bareChave = "43260412345678000190650010000123451123456782";
        assertEquals(bareChave, decoder.decode(qrPhoto(bareChave)));
    }

    @Test
    void decode_survivesPhotoLikePadding() throws Exception {
        // QR floating inside a larger "photo" with margins, as a cropped
        // gallery upload would look.
        var matrix = new QRCodeWriter().encode(QR_PAYLOAD, BarcodeFormat.QR_CODE, 300, 300);
        var qrImage = MatrixToImageWriter.toBufferedImage(matrix);
        var photo = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = photo.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 800);
        graphics.drawImage(qrImage, 150, 250, null);
        graphics.dispose();
        var file = new MockMultipartFile("file", "qr.png", "image/png", PhotoUploadValidatorTest.pngBytes(photo));

        assertEquals(QR_PAYLOAD, decoder.decode(file));
    }

    @Test
    void decode_rejectsPhotoWithoutQr() throws Exception {
        var blank = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        var file = new MockMultipartFile("file", "blank.png", "image/png", PhotoUploadValidatorTest.pngBytes(blank));

        var exception = assertThrows(InvalidReceiptPhotoException.class, () -> decoder.decode(file));
        assertEquals("receipt.photo.qr.unreadable", exception.getMessageKey());
    }
}
